import cv2
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
import time
import json
import threading
import math
from websocket import create_connection

# Configure your minimum confidence to trigger an event (0.0 to 1.0)
CONFIDENCE_THRESHOLD = 0.70
WS_URL = "ws://localhost:8080"

base_options = python.BaseOptions(model_asset_path='hand_landmarker.task')
options = vision.HandLandmarkerOptions(
    base_options=base_options,
    num_hands=1,
    min_hand_detection_confidence=0.5, # Lowered to increase base detection leniency
    min_hand_presence_confidence=0.5
)
detector = vision.HandLandmarker.create_from_options(options)

def send_alert_worker(confidence_score):
    payload = {
        "event": "DISTRESS_GESTURE_DETECTED",
        "cameraId": "CAM-01-LAPTOP",
        "confidence": confidence_score,
        "timestamp": int(time.time()),
        "location": "Public Intake Counter A",
        "triage_context": "HIGH_TRAFFIC"
    }
    try:
        ws = create_connection(WS_URL, timeout=2)
        ws.send(json.dumps(payload))
        ws.close()
        print(f"[PUBLISHER] Pushed event with {confidence_score*100:.1f}% confidence")
    except Exception as e:
        print(f"[PUBLISHER ERROR] {e}")

cap = cv2.VideoCapture(0)
frame_count = 0
last_trigger_time = 0
cached_landmarks = None
current_confidence = 0.0

window_name = "SenyAlert - Lenient Vision Engine"
cv2.namedWindow(window_name)

while cap.isOpened():
    ret, frame = cap.read()
    if not ret: break

    frame_count += 1
    frame = cv2.resize(frame, (640, 360))

    if frame_count % 6 == 0:
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
        result = detector.detect(mp_image)

        if result.hand_landmarks:
            cached_landmarks = result.hand_landmarks[0]
        else:
            cached_landmarks = None
            current_confidence = 0.0

    if cached_landmarks:
        lm = cached_landmarks
        score = 0.0
        
        # 1. Orientation check: Wrist (0) must be lower than Middle MCP (9)
        is_upright = lm[0].y > lm[9].y
        
        if is_upright:
            # 2. Thumb tucked evaluation (worth 30% of confidence)
            # Uses distance relative to the palm to be more forgiving than strict X-coordinates
            thumb_to_pinky_base = math.hypot(lm[4].x - lm[17].x, lm[4].y - lm[17].y)
            if thumb_to_pinky_base < 0.15:
                score += 0.30
                
            # 3. Fingers folded evaluation (worth 17.5% each, 70% total)
            # Lenient check: Fingertip just needs to be below the PIP joint
            for tip, pip in [(8,6), (12,10), (16,14), (20,18)]:
                if lm[tip].y > lm[pip].y:
                    score += 0.175
                    
        current_confidence = score

        # Visual feedback
        color = (0, 255, 0) if current_confidence >= CONFIDENCE_THRESHOLD else (0, 165, 255)
        h, w, _ = frame.shape
        for pt in cached_landmarks:
            cx, cy = int(pt.x * w), int(pt.y * h)
            cv2.circle(frame, (cx, cy), 5, color, cv2.FILLED)

        cv2.putText(frame, f"Confidence: {current_confidence*100:.1f}%", (20, 30), 
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)

        if current_confidence >= CONFIDENCE_THRESHOLD:
            cv2.putText(frame, "DISTRESS THRESHOLD MET", (20, 60), 
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)

    current_time = time.time()
    if current_confidence >= CONFIDENCE_THRESHOLD and (current_time - last_trigger_time > 3.0):
        last_trigger_time = current_time
        threading.Thread(target=send_alert_worker, args=(current_confidence,), daemon=True).start()

    cv2.imshow(window_name, frame)
    if cv2.waitKey(1) & 0xFF == 27 or cv2.getWindowProperty(window_name, cv2.WND_PROP_VISIBLE) < 1:
        break

cap.release()
cv2.destroyAllWindows()