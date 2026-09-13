import cv2
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
import time
import json
import threading
from websocket import create_connection

# Initialize MediaPipe Tasks Hand Landmarker
base_options = python.BaseOptions(model_asset_path='hand_landmarker.task')
options = vision.HandLandmarkerOptions(
    base_options=base_options,
    num_hands=1,
    min_hand_detection_confidence=0.7,
    min_hand_presence_confidence=0.5
)
detector = vision.HandLandmarker.create_from_options(options)

WS_URL = "ws://localhost:8080"

def send_alert_worker():
    """Pushes JSON event payload to Java WebSocket server off the video thread."""
    payload = {
        "event": "DISTRESS_GESTURE_DETECTED",
        "cameraId": "CAM-01-LAPTOP",
        "confidence": 0.95,
        "timestamp": int(time.time()),
        "location": "Public Intake Counter A",
        "triage_context": "HIGH_TRAFFIC"
    }
    try:
        ws = create_connection(WS_URL, timeout=2)
        ws.send(json.dumps(payload))
        ws.close()
        print(f"[PUBLISHER] Successfully pushed event to {WS_URL}")
    except Exception as e:
        print(f"[PUBLISHER ERROR] Could not reach Java dispatch server: {e}")

cap = cv2.VideoCapture(0)
frame_count = 0
last_trigger_time = 0
cached_landmarks = None
gesture_detected = False

window_name = "SenyAlert - Vision Ingestion Engine"
cv2.namedWindow(window_name)

while cap.isOpened():
    ret, frame = cap.read()
    if not ret:
        break

    frame_count += 1
    frame = cv2.resize(frame, (640, 360))

    # Evaluate every 6th frame (~5 FPS) to conserve edge compute cycles
    if frame_count % 6 == 0:
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
        result = detector.detect(mp_image)

        if result.hand_landmarks:
            cached_landmarks = result.hand_landmarks[0]
        else:
            cached_landmarks = None
            gesture_detected = False

    if cached_landmarks:
        lm = cached_landmarks

        # 1. Orientation check: Wrist (0) below Middle MCP (9)
        is_upright = lm[0].y > lm[9].y

        # 2. Thumb folded: Thumb tip (4) across palm
        thumb_tucked = lm[17].x < lm[4].x < lm[5].x or lm[5].x < lm[4].x < lm[17].x

        # 3. Fist closure: Fingertips (8, 12, 16, 20) drop below PIP knuckles (6, 10, 14, 18)
        fingers_folded = (
            lm[8].y > lm[6].y and
            lm[12].y > lm[10].y and
            lm[16].y > lm[14].y and
            lm[20].y > lm[18].y
        )

        gesture_detected = is_upright and thumb_tucked and fingers_folded

        point_color = (0, 255, 0) if gesture_detected else (0, 0, 255)
        h, w, _ = frame.shape
        for pt in cached_landmarks:
            cx, cy = int(pt.x * w), int(pt.y * h)
            cv2.circle(frame, (cx, cy), 5, point_color, cv2.FILLED)

        if gesture_detected:
            cv2.putText(frame, "DISTRESS DETECTED", (20, 45), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)

    current_time = time.time()
    if gesture_detected and (current_time - last_trigger_time > 3.0):
        last_trigger_time = current_time
        threading.Thread(target=send_alert_worker, daemon=True).start()

    cv2.imshow(window_name, frame)
    key = cv2.waitKey(1) & 0xFF
    if key == 27 or cv2.getWindowProperty(window_name, cv2.WND_PROP_VISIBLE) < 1:
        break

cap.release()
cv2.destroyAllWindows()