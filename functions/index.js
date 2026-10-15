/**
 * NullChat Cloud Functions.
 *
 *  Storage growth is bounded by four mechanisms:
 *   1. onMessageDeleted  -> remove the objects when a message is deleted
 *   2. cleanupOrphans    -> sweep media with refCount 0 that aren't favorited
 *   3. storageSweep      -> safety net for objects with no index row
 *   4. (GCS lifecycle)   -> hard 90-day delete, configured in firebase/lifecycle.json
 *
 *  Plus: upload logging (for abuse detection) and optional image moderation.
 *
 *  Deploy:  firebase deploy --only functions,firestore:rules,storage
 */

const { onDocumentDeleted } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onObjectFinalized } = require("firebase-functions/v2/storage");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const logger = require("firebase-functions/logger");

initializeApp();
setGlobalOptions({ region: "us-central1", maxInstances: 10 });

const db = getFirestore();
const bucket = getStorage().bucket();

const DAILY_UPLOAD_LIMIT = 20;
const USER_QUOTA_BYTES = 100 * 1024 * 1024; // 100 MB
const ORPHAN_GRACE_MS = 7 * 24 * 60 * 60 * 1000;

/* ------------------------------------------------------------------ *
 * 1. Message deleted -> delete its Storage objects + refund quota
 * ------------------------------------------------------------------ */
exports.onMessageDeleted = onDocumentDeleted(
  "chats/{chatId}/messages/{msgId}",
  async (event) => {
    const data = event.data?.data();
    if (!data) return;

    const paths = [data.storagePath, data.thumbPath].filter(Boolean);
    await Promise.all(
      paths.map((p) =>
        bucket.file(p).delete().catch((e) => {
          if (e.code !== 404) logger.warn("storage delete failed", { p, error: e.message });
        })
      )
    );

    const owner = data.senderId;
    if (data.mediaId && owner) {
      await db
        .doc(`user_media_index/${owner}/items/${data.mediaId}`)
        .set(
          { refCount: FieldValue.increment(-1), lastUsedAt: FieldValue.serverTimestamp() },
          { merge: true }
        )
        .catch(() => {});
    }

    if (owner && data.sizeBytes) {
      await db
        .doc(`users/${owner}`)
        .set({ storageBytes: FieldValue.increment(-data.sizeBytes) }, { merge: true });
    }
  }
);

/* ------------------------------------------------------------------ *
 * 2. Orphan sweep: index rows with refCount <= 0, not favorited, old
 * ------------------------------------------------------------------ */
exports.cleanupOrphans = onSchedule("every 24 hours", async () => {
  const cutoff = Date.now() - ORPHAN_GRACE_MS;
  const snap = await db.collectionGroup("items").where("refCount", "<=", 0).get();

  let removed = 0;
  for (const doc of snap.docs) {
    const d = doc.data();
    if (d.favorite) continue;
    if ((d.lastUsedAt?.toMillis?.() ?? 0) > cutoff) continue;

    await Promise.all(
      [
        bucket.file(d.storagePath).delete().catch(() => {}),
        d.thumbPath ? bucket.file(d.thumbPath).delete().catch(() => {}) : null,
        d.uid
          ? db
              .doc(`users/${d.uid}`)
              .set({ storageBytes: FieldValue.increment(-(d.sizeBytes || 0)) }, { merge: true })
          : null,
        doc.ref.delete(),
      ].filter(Boolean)
    );
    removed++;
  }

  logger.info("orphan sweep complete", { scanned: snap.size, removed });
});

/* ------------------------------------------------------------------ *
 * 3. Storage safety net: objects older than 24h with no index row
 *    (only runs over objects that carry customMetadata.mediaId)
 * ------------------------------------------------------------------ */
exports.storageSweep = onSchedule(
  { schedule: "every 24 hours", timeoutSeconds: 540 },
  async () => {
    const [files] = await bucket.getFiles({ prefix: "chat_media/", maxResults: 1000 });
    const now = Date.now();
    let removed = 0;

    for (const file of files) {
      const created = new Date(file.metadata.timeCreated).getTime();
      if (now - created < 24 * 60 * 60 * 1000) continue;

      const mediaId = (file.metadata.metadata || {}).mediaId;
      if (!mediaId) continue;

      const idx = await db
        .collectionGroup("items")
        .where("mediaId", "==", mediaId)
        .limit(1)
        .get();

      if (idx.empty) {
        await file.delete().catch(() => {});
        removed++;
      }
    }
    logger.info("storage sweep complete", { scanned: files.length, removed });
  }
);

/* ------------------------------------------------------------------ *
 * 4. Log every uploaded object so abuse is easy to spot
 * ------------------------------------------------------------------ */
exports.logUpload = onObjectFinalized({ memory: "256MiB" }, async (event) => {
  const name = event.data.name || "";
  const match = name.match(/^chat_media\/([^/]+)\//);
  if (!match) return;

  await db.collection("upload_logs").add({
    uid: match[1],
    path: name,
    sizeBytes: Number(event.data.size || 0),
    contentType: event.data.contentType || "",
    timeCreated: event.data.timeCreated || null,
    createdAt: FieldValue.serverTimestamp(),
  });
});

/* ------------------------------------------------------------------ *
 * 5. Optional: image moderation via Cloud Vision SafeSearch.
 *    Requires the Vision API + Blaze. Enable only if you want it —
 *    it costs one call per image.
 * ------------------------------------------------------------------ */
let visionClient = null;
function getVision() {
  if (!visionClient) {
    // Lazy require so the function deploys even if the package isn't installed.
    const vision = require("@google-cloud/vision");
    visionClient = new vision.ImageAnnotatorClient();
  }
  return visionClient;
}

exports.moderateImages = onObjectFinalized({ memory: "512MiB", timeoutSeconds: 120 }, async (event) => {
  const name = event.data.name || "";
  const ct = event.data.contentType || "";
  if (!ct.startsWith("image/")) return;
  if (!/^chat_media\//.test(name)) return;

  try {
    const client = getVision();
    const [res] = await client.safeSearchDetection(`gs://${bucket.name}/${name}`);
    const ss = res.safeSearchAnnotation || {};
    const flagged =
      ["LIKELY", "VERY_LIKELY"].includes(ss.adult) ||
      ["LIKELY", "VERY_LIKELY"].includes(ss.violence);

    if (flagged) {
      await db.collection("moderation").add({
        path: name,
        adult: ss.adult || null,
        violence: ss.violence || null,
        racy: ss.racy || null,
        createdAt: FieldValue.serverTimestamp(),
      });
      logger.warn("moderation flagged upload", { path: name, adult: ss.adult });
    }
  } catch (e) {
    logger.warn("moderation skipped", { path: name, error: e.message });
  }
});

/* ------------------------------------------------------------------ *
 * 6. OPTIONAL tamper-proof quota: server-side reserve.
 *    If you use this, make the client call it instead of UploadLimiter
 *    and set users/{uid} writes to `if false` in firestore.rules.
 * ------------------------------------------------------------------ */
exports.reserveUpload = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in required.");

  const sizeBytes = Number(request.data?.sizeBytes || 0);
  if (!Number.isFinite(sizeBytes) || sizeBytes <= 0) {
    throw new HttpsError("invalid-argument", "sizeBytes is required.");
  }

  const userRef = db.doc(`users/${uid}`);
  const dayKey = new Date().toISOString().slice(0, 10).replace(/-/g, "");
  const dayRef = userRef.collection("uploadStats").doc(dayKey);

  return db.runTransaction(async (txn) => {
    const user = await txn.get(userRef);
    const used = user.get("storageBytes") || 0;
    const quota = user.get("storageQuota") || USER_QUOTA_BYTES;
    if (used + sizeBytes > quota) {
      throw new HttpsError("resource-exhausted", "Storage quota exceeded.");
    }

    const day = await txn.get(dayRef);
    const count = day.get("count") || 0;
    if (count >= DAILY_UPLOAD_LIMIT) {
      throw new HttpsError("resource-exhausted", "Daily upload limit reached.");
    }

    txn.set(userRef, { storageBytes: used + sizeBytes, storageQuota: quota }, { merge: true });
    txn.set(dayRef, { count: count + 1 }, { merge: true });
    return { ok: true, used: used + sizeBytes, quota };
  });
});
