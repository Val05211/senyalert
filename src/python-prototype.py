import cv2
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
import time
import json
import threading
import math
import os
import glob
import base64
import datetime
import numpy as np
from collections import deque
from websocket import create_connection

# =============================================================================
# CONFIG
# =============================================================================
WS_URL = "ws://localhost:8080"

# Alert firing
CONFIDENCE_THRESHOLD = 0.70
ALERT_COOLDOWN_SEC = 3.0

# Capture / detection resolution.
# NOTE: previously every frame was force-resized to 640x360 before detection,
# which crushes far-away hands down to just a few pixels. We now capture at a
# higher resolution and only downscale for the on-screen preview, not for
# detection. If your webcam can't do 1280x720, OpenCV will silently fall back
# to its max supported resolution.
CAPTURE_WIDTH, CAPTURE_HEIGHT = 1280, 720
PREVIEW_WIDTH, PREVIEW_HEIGHT = 960, 540

# Process every Nth frame. Lowered from 6 -> 3 so the motion sequence
# (open -> tuck -> close) has enough temporal samples to be tracked reliably.
PROCESS_EVERY_N_FRAMES = 3

# --- Multi-hand tracking (multiple people in frame, e.g. CCTV) ---
MAX_HANDS = 4                  # how many hands MediaPipe looks for per frame
HAND_MATCH_MAX_DIST = 0.20     # max normalized wrist-movement distance to treat as "same hand" across frames
HAND_TRACK_STALE_SEC = 1.0     # forget a tracked hand if it hasn't been seen for this long

# --- Gesture sequence timing (tune these against real footage later) ---
OPEN_HOLD_FRAMES = 2          # consecutive "open hand" readings required to start the sequence
CLOSE_HOLD_FRAMES = 3         # consecutive "closed + tucked" readings required to confirm
SEQUENCE_MAX_DURATION_SEC = 4.0   # open->close must complete within this window
OPEN_LOST_GRACE_SEC = 0.6     # briefly tolerate a dropped hand-detection frame without resetting

# --- Geometry thresholds (scale-invariant, relative to hand size) ---
# hand_scale = distance(wrist, middle-finger MCP). Every other distance is
# measured as a ratio of this, so the same physical gesture reads the same
# whether the hand is close to the camera or far away.
THUMB_TUCKED_RATIO = 0.55      # thumb tip distance to palm center / hand_scale
FINGER_EXTENDED_MARGIN = 0.05  # extra margin (as ratio of hand_scale) tip must clear PIP by, when open
FINGER_CURLED_MARGIN = 0.05    # extra margin tip must drop past MCP by, when closed

# --- Evidence capture (snapshot + attire color, for investigation reference) ---
# NOTE: we only have HAND landmarks, not a full body-pose model, so the
# torso/leg regions below are ESTIMATED from the hand's position and size.
# This is approximate by nature. If you later add MediaPipe's Pose landmarker
# (tracks shoulders/hips/knees directly), swap crop_body_regions() below to
# use those landmarks instead -- everything downstream (color classification,
# snapshot saving, payload) stays the same.
SNAPSHOT_DIR = "snapshots"
SNAPSHOT_RETENTION_DAYS = 7          # snapshots older than this are auto-deleted
SNAPSHOT_CLEANUP_INTERVAL_SEC = 6 * 60 * 60   # re-check for old snapshots every 6 hours
SNAPSHOT_THUMBNAIL_WIDTH = 320        # base64 thumbnail embedded in the alert payload
os.makedirs(SNAPSHOT_DIR, exist_ok=True)

base_options = python.BaseOptions(model_asset_path='hand_landmarker.task')
options = vision.HandLandmarkerOptions(
    base_options=base_options,
    num_hands=MAX_HANDS,
    min_hand_detection_confidence=0.5,
    min_hand_presence_confidence=0.5
)
detector = vision.HandLandmarker.create_from_options(options)

FINGER_JOINTS = {
    # name: (tip, pip, mcp)
    "index":  (8, 6, 5),
    "middle": (12, 10, 9),
    "ring":   (16, 14, 13),
    "pinky":  (20, 18, 17),
}


def cleanup_old_snapshots():
    """Delete snapshots older than SNAPSHOT_RETENTION_DAYS, then reschedule itself."""
    cutoff = time.time() - SNAPSHOT_RETENTION_DAYS * 86400
    removed = 0
    for f in glob.glob(os.path.join(SNAPSHOT_DIR, "*.jpg")):
        try:
            if os.path.getmtime(f) < cutoff:
                os.remove(f)
                removed += 1
        except OSError:
            pass
    if removed:
        print(f"[RETENTION] Deleted {removed} snapshot(s) older than {SNAPSHOT_RETENTION_DAYS} days")
    t = threading.Timer(SNAPSHOT_CLEANUP_INTERVAL_SEC, cleanup_old_snapshots)
    t.daemon = True
    t.start()


def classify_color(bgr_crop):
    """
    Rough dominant-color classification for a cropped region.
    Uses HSV so lighting brightness doesn't throw off the hue-based bucketing
    as much as raw RGB would. This is a heuristic, not a calibrated color
    sensor -- expect it to need tuning once tested against your actual
    camera/lighting (and it will be materially less reliable on IR/greyscale
    night-vision CCTV footage, which has no color information to read at all).
    """
    if bgr_crop is None or bgr_crop.size == 0:
        return "Unknown"

    hsv = cv2.cvtColor(bgr_crop, cv2.COLOR_BGR2HSV)
    h = float(np.median(hsv[:, :, 0]))
    s = float(np.median(hsv[:, :, 1]))
    v = float(np.median(hsv[:, :, 2]))

    if v < 50:
        return "Black"
    if s < 35 and v > 200:
        return "White"
    if s < 50:
        return "Gray"
    if v < 130 and 5 <= h <= 20:
        return "Brown"
    if h < 10 or h >= 170:
        return "Red"
    if h < 22:
        return "Orange"
    if h < 35:
        return "Yellow"
    if h < 85:
        return "Green"
    if h < 125:
        return "Blue"
    if h < 150:
        return "Purple"
    return "Pink"


def crop_body_regions(frame, lm):
    """
    Estimate rough "torso" and "legs" crop boxes below the detected hand,
    scaled by the hand's own size. Heuristic and approximate -- see the
    module-level note near SNAPSHOT_DIR for the recommended upgrade path
    (MediaPipe Pose landmarks) once you have real deployment footage to
    validate against.
    """
    h, w = frame.shape[:2]
    wrist = lm[0]
    middle_mcp = lm[9]
    hand_scale_px = math.hypot((wrist.x - middle_mcp.x) * w, (wrist.y - middle_mcp.y) * h)
    if hand_scale_px < 1:
        return None, None

    cx = wrist.x * w
    top_y = wrist.y * h + 0.8 * hand_scale_px

    def box(y0, height, half_width):
        y0c = int(np.clip(y0, 0, h - 1))
        y1c = int(np.clip(y0 + height, 0, h))
        x0c = int(np.clip(cx - half_width, 0, w - 1))
        x1c = int(np.clip(cx + half_width, 0, w))
        # Require a meaningful amount of pixels -- a sliver clipped at the
        # frame edge (e.g. "legs" region falling below a close-up shot)
        # should read as Unknown, not guess a color from a few stray pixels.
        if (y1c - y0c) < 8 or (x1c - x0c) < 8:
            return None
        return frame[y0c:y1c, x0c:x1c]

    torso = box(top_y, 3.2 * hand_scale_px, 2.5 * hand_scale_px)
    legs = box(top_y + 3.2 * hand_scale_px, 3.8 * hand_scale_px, 2.8 * hand_scale_px)
    return torso, legs


def capture_evidence(frame, landmarks, now):
    """
    On a confirmed alert: save a full snapshot to disk, estimate attire
    color, and build a small base64 thumbnail for the alert payload.
    Returns (snapshot_path, description_text, thumbnail_base64_or_None).
    """
    torso_crop, legs_crop = crop_body_regions(frame, landmarks)
    top_color = classify_color(torso_crop) if torso_crop is not None else "Unknown"
    bottom_color = classify_color(legs_crop) if legs_crop is not None else "Unknown"
    description = f"Top: {top_color}, Bottom: {bottom_color}"

    ts = datetime.datetime.fromtimestamp(now)
    filename = f"alert_{ts.strftime('%Y%m%d_%H%M%S')}.jpg"
    snapshot_path = os.path.join(SNAPSHOT_DIR, filename)
    cv2.imwrite(snapshot_path, frame)

    thumb_b64 = None
    try:
        th_h = int(frame.shape[0] * (SNAPSHOT_THUMBNAIL_WIDTH / frame.shape[1]))
        thumb = cv2.resize(frame, (SNAPSHOT_THUMBNAIL_WIDTH, th_h))
        ok, buf = cv2.imencode(".jpg", thumb, [cv2.IMWRITE_JPEG_QUALITY, 70])
        if ok:
            thumb_b64 = base64.b64encode(buf).decode("ascii")
    except Exception as e:
        print(f"[EVIDENCE] Thumbnail encode failed: {e}")

    return snapshot_path, description, thumb_b64


def send_alert_worker(confidence_score, hand_id=None, snapshot_path=None, description=None, thumbnail_b64=None):
    now = time.time()
    payload = {
        "event": "DISTRESS_GESTURE_DETECTED",
        "cameraId": "CAM-01-LAPTOP",
        "handId": hand_id,   # which tracked hand triggered this, useful when several are in frame
        "confidence": confidence_score,
        "timestamp": int(now),
        "timestamp_readable": datetime.datetime.fromtimestamp(now).strftime("%Y-%m-%d %H:%M:%S"),
        "location": "Public Intake Counter A",
        "triage_context": "HIGH_TRAFFIC",
        "attire_description": description,
        "snapshot_path": snapshot_path,
        "snapshot_thumbnail_base64": thumbnail_b64,
    }
    try:
        ws = create_connection(WS_URL, timeout=2)
        ws.send(json.dumps(payload))
        ws.close()
        print(f"[PUBLISHER] Pushed event with {confidence_score*100:.1f}% confidence | {description} | {snapshot_path}")
    except Exception as e:
        print(f"[PUBLISHER ERROR] {e}")


def analyze_frame(lm):
    """
    Extract a scale-invariant geometric reading from one frame of landmarks.
    Returns a dict describing whether this frame looks like an "open hand"
    frame, a "closed + tucked" frame, or neither, plus the raw ratios for
    debugging/on-screen display.
    """
    wrist = lm[0]
    middle_mcp = lm[9]
    index_mcp = lm[5]
    pinky_mcp = lm[17]
    thumb_tip = lm[4]

    hand_scale = math.hypot(wrist.x - middle_mcp.x, wrist.y - middle_mcp.y)
    if hand_scale < 1e-6:
        return None

    is_upright = wrist.y > middle_mcp.y

    # Palm center = average of the four finger MCPs (stable reference point
    # that doesn't move much regardless of thumb/finger position).
    palm_cx = (index_mcp.x + middle_mcp.x + pinky_mcp.x + lm[13].x) / 4.0
    palm_cy = (index_mcp.y + middle_mcp.y + pinky_mcp.y + lm[13].y) / 4.0

    thumb_to_palm_ratio = math.hypot(thumb_tip.x - palm_cx, thumb_tip.y - palm_cy) / hand_scale
    thumb_tucked = thumb_to_palm_ratio < THUMB_TUCKED_RATIO

    extended_count = 0
    curled_count = 0
    for tip_i, pip_i, mcp_i in FINGER_JOINTS.values():
        tip, pip, mcp = lm[tip_i], lm[pip_i], lm[mcp_i]
        # Extended: tip clearly above (lower y than) the PIP joint.
        if (pip.y - tip.y) / hand_scale > FINGER_EXTENDED_MARGIN:
            extended_count += 1
        # Curled: tip has dropped past the MCP (knuckle), not just the PIP.
        # This is stricter than the original "past PIP" rule, which counted
        # a lot of partially-bent fingers (like a relaxed fist) as fully folded.
        if (tip.y - mcp.y) / hand_scale > FINGER_CURLED_MARGIN:
            curled_count += 1

    # "Open" only requires fingers spread -- it's just the gate that keeps a
    # hand entering frame already as a fist (e.g. a punch) from qualifying.
    # Thumb position is exactly what changes during the gesture itself, so
    # it can't also be a requirement for this earlier "open" phase.
    is_open = is_upright and extended_count >= 3
    is_closed_tucked = is_upright and thumb_tucked and curled_count == 4

    return {
        "is_upright": is_upright,
        "is_open": is_open,
        "is_closed_tucked": is_closed_tucked,
        "thumb_to_palm_ratio": thumb_to_palm_ratio,
        "curled_count": curled_count,
        "extended_count": extended_count,
    }


class SignalStateMachine:
    """
    Tracks the open-hand -> thumb-tuck -> closed-fist motion over time.
    A static fist (e.g. entering frame already clenched, like a punch) never
    satisfies the OPEN phase, so it cannot trigger the WAITING_CLOSE ->
    CONFIRMED transition on its own.
    """
    IDLE = "IDLE"
    WAITING_CLOSE = "WAITING_CLOSE"

    def __init__(self):
        self.state = self.IDLE
        self.open_streak = 0
        self.close_streak = 0
        self.open_confirmed_at = None
        self.last_hand_seen_at = None

    def reset(self):
        self.state = self.IDLE
        self.open_streak = 0
        self.close_streak = 0
        self.open_confirmed_at = None

    def update(self, reading, now):
        """
        reading: dict from analyze_frame(), or None if no hand detected this frame.
        Returns (confirmed: bool, status_text: str)
        """
        if reading is None:
            # Tolerate a brief detection dropout without losing the sequence,
            # since MediaPipe can miss a frame here and there mid-motion.
            if self.last_hand_seen_at and (now - self.last_hand_seen_at) > OPEN_LOST_GRACE_SEC:
                self.reset()
            return False, self.state

        self.last_hand_seen_at = now

        if self.state == self.IDLE:
            if reading["is_open"]:
                self.open_streak += 1
                if self.open_streak >= OPEN_HOLD_FRAMES:
                    self.state = self.WAITING_CLOSE
                    self.open_confirmed_at = now
                    self.close_streak = 0
            else:
                self.open_streak = 0
            return False, self.state

        if self.state == self.WAITING_CLOSE:
            if now - self.open_confirmed_at > SEQUENCE_MAX_DURATION_SEC:
                self.reset()
                return False, self.state

            if reading["is_closed_tucked"]:
                self.close_streak += 1
                if self.close_streak >= CLOSE_HOLD_FRAMES:
                    self.reset()
                    return True, "CONFIRMED"
            else:
                # Just decay -- fingers commonly stay extended for a while as
                # the thumb tucks in underneath them mid-motion, which is not
                # the same as abandoning the gesture. Only the timeout above
                # or losing the hand entirely should bail out of the sequence.
                self.close_streak = max(0, self.close_streak - 1)
            return False, self.state

        return False, self.state


class HandTrack:
    """One tracked hand: its own state machine, position, and alert cooldown."""

    def __init__(self, track_id):
        self.id = track_id
        self.state_machine = SignalStateMachine()
        self.last_pos = None          # normalized (x, y) wrist position, for frame-to-frame matching
        self.last_trigger_time = 0.0  # per-hand cooldown, so one person's repeats don't block another's alert


class MultiHandTracker:
    """
    Matches each frame's detected hands to persistent tracks (by nearest
    wrist position) so each hand keeps its own open->tuck->close progress
    across frames instead of a single global state getting confused when
    more than one hand/person is in view.
    """

    def __init__(self, max_dist=HAND_MATCH_MAX_DIST, stale_sec=HAND_TRACK_STALE_SEC):
        self.tracks = {}
        self.next_id = 0
        self.max_dist = max_dist
        self.stale_sec = stale_sec

    def update(self, hands_landmarks_list, now):
        """
        hands_landmarks_list: list of per-hand landmark lists for this frame
        (result.hand_landmarks straight from the detector), possibly empty.
        Returns a list of dicts: {id, landmarks, reading, confirmed, state, track}
        for the hands seen this frame.
        """
        positions = [(lm[0].x, lm[0].y) for lm in hands_landmarks_list]

        unmatched_track_ids = set(self.tracks.keys())
        assignments = {}
        for i, pos in enumerate(positions):
            best_id, best_dist = None, None
            for tid in unmatched_track_ids:
                tpos = self.tracks[tid].last_pos
                if tpos is None:
                    continue
                d = math.hypot(pos[0] - tpos[0], pos[1] - tpos[1])
                if best_dist is None or d < best_dist:
                    best_dist, best_id = d, tid
            if best_id is not None and best_dist < self.max_dist:
                assignments[i] = best_id
                unmatched_track_ids.discard(best_id)

        for i, pos in enumerate(positions):
            if i not in assignments:
                tid = self.next_id
                self.next_id += 1
                self.tracks[tid] = HandTrack(tid)
                assignments[i] = tid

        results = []
        matched_ids = set()
        for i, lm in enumerate(hands_landmarks_list):
            tid = assignments[i]
            track = self.tracks[tid]
            track.last_pos = positions[i]
            matched_ids.add(tid)

            reading = analyze_frame(lm)
            confirmed, state = track.state_machine.update(reading, now)
            results.append({
                "id": tid, "landmarks": lm, "reading": reading,
                "confirmed": confirmed, "state": state, "track": track,
            })

        # Age out tracks not seen this frame; let their state machine's own
        # grace/timeout logic run so a brief dropout doesn't wipe progress.
        for tid in list(self.tracks.keys()):
            if tid in matched_ids:
                continue
            track = self.tracks[tid]
            track.state_machine.update(None, now)
            lhs = track.state_machine.last_hand_seen_at
            if lhs is not None and (now - lhs) > self.stale_sec:
                del self.tracks[tid]

        return results


cap = cv2.VideoCapture(0)
cap.set(cv2.CAP_PROP_FRAME_WIDTH, CAPTURE_WIDTH)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT, CAPTURE_HEIGHT)

frame_count = 0
cached_results = []   # last processed frame's per-hand results, redrawn between processed frames
last_confirmed_flash_until = 0
tracker = MultiHandTracker()

cleanup_old_snapshots()  # also self-reschedules every SNAPSHOT_CLEANUP_INTERVAL_SEC

window_name = "SenyAlert - Vision Engine"
cv2.namedWindow(window_name)

while cap.isOpened():
    ret, frame = cap.read()
    if not ret:
        break

    frame_count += 1
    now = time.time()

    if frame_count % PROCESS_EVERY_N_FRAMES == 0:
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
        result = detector.detect(mp_image)

        cached_results = tracker.update(result.hand_landmarks or [], now)

        for r in cached_results:
            track = r["track"]
            if r["confirmed"] and (now - track.last_trigger_time > ALERT_COOLDOWN_SEC):
                track.last_trigger_time = now
                last_confirmed_flash_until = now + 1.0
                # Capture snapshot/color synchronously (cheap, needs current
                # frame + this hand's landmarks) then hand the network send
                # off to a background thread as before.
                snapshot_path, description, thumb_b64 = capture_evidence(frame, r["landmarks"], now)
                threading.Thread(
                    target=send_alert_worker,
                    args=(1.0, r["id"], snapshot_path, description, thumb_b64),
                    daemon=True
                ).start()

    # --- Visual feedback (preview only; detection already ran at full res) ---
    preview = cv2.resize(frame, (PREVIEW_WIDTH, PREVIEW_HEIGHT))
    h, w, _ = preview.shape

    for r in cached_results:
        color = (0, 255, 0) if r["state"] == SignalStateMachine.WAITING_CLOSE else (0, 165, 255)
        for pt in r["landmarks"]:
            cx, cy = int(pt.x * w), int(pt.y * h)
            cv2.circle(preview, (cx, cy), 4, color, cv2.FILLED)
        wrist = r["landmarks"][0]
        label_x, label_y = int(wrist.x * w), max(15, int(wrist.y * h) - 15)
        cv2.putText(preview, f"#{r['id']} {r['state']}", (label_x, label_y),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1)

    cv2.putText(preview, f"Hands tracked: {len(cached_results)}", (20, 30),
                cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)

    if now < last_confirmed_flash_until:
        cv2.putText(preview, "DISTRESS SIGNAL CONFIRMED", (20, 60),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)

    cv2.imshow(window_name, preview)
    if cv2.waitKey(1) & 0xFF == 27 or cv2.getWindowProperty(window_name, cv2.WND_PROP_VISIBLE) < 1:
        break

cap.release()
cv2.destroyAllWindows()
