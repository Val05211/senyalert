import cv2
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
import time
import json
import threading

base_options = python.BaseOptions(model_asset_path='hand_landmarker.task')
options = vision.HandLandmarkerOptions(
    base_options=base_options,
    num_hands=1,
    min_hand_detection_confidence=0.7,
    min_hand_presence_confidence=0.5
)
detector = vision.HandLandmarker.create_from_options(options)

def push_event_payload():
    payload = {
        "event": "DISTRESS_GESTURE_DETECTED",
        "cameraId": "CAM-01-LAPTOP",
        "confidence": 0.94,
        "timestamp": int(time.time()),
        "zone": "Public Terminal A",
        "threatProfile": "HIGH_TRAFFIC_INTERVIEW"
    } #[cite: 2]
    
    json_data = json.dumps(payload)
    print(f"\n[PUBLISHER] Pushing Event: {json_data}")

cap = cv2.VideoCapture(0)

# Initialize cache variables to stop the UI from flashing
frame_count = 0
last_trigger_time = 0
cached_landmarks = None
gesture_detected = False

# Name the window explicitly so we can track the 'X' close button
window_name = "Python Edge Detection GUI"
cv2.namedWindow(window_name)

while cap.isOpened():
    ret, frame = cap.read()
    if not ret:
        break
        
    frame_count += 1
    frame = cv2.resize(frame, (640, 360))
    
    # Run the heavy MediaPipe detection only every 6th frame (~5 FPS) to save CPU
    if frame_count % 6 == 0:
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
        result = detector.detect(mp_image)
        
        # Update cache if a hand is found
        if result.hand_landmarks:
            cached_landmarks = result.hand_landmarks[0]
        else:
            cached_landmarks = None
            gesture_detected = False

    # Draw the UI on EVERY frame using the cached data
    if cached_landmarks:
        lm = cached_landmarks
        
        # 1. Strict Upright Orientation Check (Fixes "Hand on Head" False Positive)
        # In OpenCV, Y=0 is the top of the screen. Wrist (0) must have a higher Y value than Middle Knuckle (9).
        is_upright = lm[0].y > lm[9].y
        
        # 2. State 2: Thumb Folded across palm
        thumb_tucked = lm[17].x < lm[4].x < lm[5].x or lm[5].x < lm[4].x < lm[17].x
        
        # 3. State 3: Fingers Closed over thumb
        fingers_folded = (
            lm[8].y > lm[6].y and
            lm[12].y > lm[10].y and
            lm[16].y > lm[14].y and
            lm[20].y > lm[18].y
        )

        # Require all states AND upright orientation to trigger
        if is_upright and thumb_tucked and fingers_folded:
            gesture_detected = True
        else:
            gesture_detected = False

        # Dynamic Color Swap: Green if detected, Red if not
        point_color = (0, 255, 0) if gesture_detected else (0, 0, 255)
        
        h, w, _ = frame.shape
        for landmark in cached_landmarks:
            cx, cy = int(landmark.x * w), int(landmark.y * h)
            cv2.circle(frame, (cx, cy), 6, point_color, cv2.FILLED)

        if gesture_detected:
            cv2.putText(frame, "DISTRESS SIGNAL DETECTED", (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 3)

    # Fire event payload to the Java dashboard with a 3-second cooldown
    current_time = time.time()
    if gesture_detected and (current_time - last_trigger_time > 3):
        last_trigger_time = current_time
        threading.Thread(target=push_event_payload).start()

    cv2.imshow(window_name, frame)
    
    # Check for 'Esc' key (ASCII 27) OR the window 'X' close button
    key = cv2.waitKey(1) & 0xFF
    if key == 27 or cv2.getWindowProperty(window_name, cv2.WND_PROP_VISIBLE) < 1:
        break

cap.release()
cv2.destroyAllWindows()