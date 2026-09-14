import cv2
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
import time
import json
import threading
import collections
import websocket
import math
import os

# Default Configurations (Overridden by Java Settings Panel)
CONFIG = {
    "confidence_threshold": 0.70,
    "fingers": {"thumb": True, "index": True, "middle": True, "ring": True, "pinky": True},
    "pre_event_sec": 5,
    "post_event_sec": 5,
    "paused": False,
    "resolution": (640, 360)
}

WS_URL = "ws://localhost:8080"
FPS_TARGET = 10 

# Ring buffer for Pre-Event recording
frame_buffer = collections.deque(maxlen=CONFIG["pre_event_sec"] * FPS_TARGET)
is_recording_event = False
post_event_frames_left = 0
current_video_writer = None
current_event_id = None
ws_app = None

base_options = python.BaseOptions(model_asset_path='hand_landmarker.task')
options = vision.HandLandmarkerOptions(
    base_options=base_options,
    num_hands=1,
    min_hand_detection_confidence=0.5,
    min_hand_presence_confidence=0.5
)
detector = vision.HandLandmarker.create_from_options(options)

def on_message(ws, message):
    """Receives JSON configuration commands from the Java Dashboard."""
    global CONFIG, frame_buffer
    try:
        data = json.loads(message)
        if data.get("action") == "UPDATE_SETTINGS":
            CONFIG.update(data["config"])
            frame_buffer = collections.deque(frame_buffer, maxlen=CONFIG["pre_event_sec"] * FPS_TARGET)
            print("[ENGINE] Settings reloaded from dashboard.")
        elif data.get("action") == "PAUSE":
            CONFIG["paused"] = data["state"]
    except Exception as e:
        print(f"[ENGINE ERROR] Failed to parse dashboard command: {e}")

def start_websocket_listener():
    global ws_app
    ws_app = websocket.WebSocketApp(WS_URL, on_message=on_message)
    while True:
        ws_app.run_forever()
        time.sleep(3) # Reconnect loop

def send_alert(confidence_score, video_filename):
    payload = {
        "event": "DISTRESS_GESTURE_DETECTED",
        "cameraId": "CAM-01-LAPTOP",
        "confidence": confidence_score,
        "timestamp": int(time.time()),
        "location": "Public Intake Counter A",
        "triage_context": "HIGH_TRAFFIC",
        "video_path": video_filename
    }
    if ws_app and ws_app.sock and ws_app.sock.connected:
        ws_app.send(json.dumps(payload))

def process_frame(frame, cached_landmarks):
    global is_recording_event, post_event_frames_left, current_video_writer, current_event_id

    # 1. Manage Ring Buffer & Recording
    frame_buffer.append(frame.copy())
    
    if is_recording_event:
        current_video_writer.write(frame)
        post_event_frames_left -= 1
        if post_event_frames_left <= 0:
            is_recording_event = False
            current_video_writer.release()
            print(f"[RECORDING] Event saved to {current_event_id}.mp4")

    if CONFIG["paused"] or cached_landmarks is None:
        return 0.0

    lm = cached_landmarks
    score = 0.0
    
    # Orientation check: Wrist (0) must be lower than Middle MCP (9)[cite: 3]
    is_upright = lm[0].y > lm[9].y
    
    if is_upright:
        # Dynamic Thumb Check using hypotenuse for leniency[cite: 3]
        if CONFIG["fingers"].get("thumb", True):
            thumb_to_pinky_base = math.hypot(lm[4].x - lm[17].x, lm[4].y - lm[17].y)
            if thumb_to_pinky_base < 0.15:
                score += 0.30
        else:
            score += 0.30 

        # Dynamic Finger Checks: Tip must fall below PIP joint[cite: 3]
        finger_map = {"index": (8,6), "middle": (12,10), "ring": (16,14), "pinky": (20,18)}
        for finger, (tip, pip) in finger_map.items():
            if CONFIG["fingers"].get(finger, True):
                if lm[tip].y > lm[pip].y:
                    score += 0.175
            else:
                score += 0.175 

    # Render Visual Feedback Dots[cite: 3]
    color = (0, 255, 0) if score >= CONFIG["confidence_threshold"] else (0, 165, 255)
    h, w, _ = frame.shape
    for pt in cached_landmarks:
        cx, cy = int(pt.x * w), int(pt.y * h)
        cv2.circle(frame, (cx, cy), 5, color, cv2.FILLED)

    cv2.putText(frame, f"Confidence: {score*100:.1f}%", (20, 30), 
                cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)

    # 2. Trigger Event & Start Video Dump
    current_time = time.time()
    if score >= CONFIG["confidence_threshold"]:
        cv2.putText(frame, "DISTRESS THRESHOLD MET", (20, 60), 
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
                    
        if not is_recording_event:
            is_recording_event = True
            post_event_frames_left = CONFIG["post_event_sec"] * FPS_TARGET
            current_event_id = f"incident_{int(current_time)}"
            
            # Resolve Absolute Path to eliminate Java file searching errors
            video_filepath = os.path.abspath(f"{current_event_id}.mp4")
            
            fourcc = cv2.VideoWriter_fourcc(*'mp4v')
            current_video_writer = cv2.VideoWriter(video_filepath, fourcc, FPS_TARGET, CONFIG["resolution"])
            
            # Dump pre-event buffer into the file immediately
            for buf_frame in frame_buffer:
                current_video_writer.write(buf_frame)

            threading.Thread(target=send_alert, args=(score, video_filepath), daemon=True).start()

    return score

# Start WS background thread
threading.Thread(target=start_websocket_listener, daemon=True).start()

cap = cv2.VideoCapture(0)
frame_count = 0
cached_landmarks = None

window_name = "SenyAlert - Vision Engine"
cv2.namedWindow(window_name)

while cap.isOpened():
    ret, frame = cap.read()
    if not ret: break

    frame = cv2.resize(frame, CONFIG["resolution"])
    frame_count += 1

    if frame_count % 3 == 0: 
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
        result = detector.detect(mp_image)
        cached_landmarks = result.hand_landmarks[0] if result.hand_landmarks else None

    current_confidence = process_frame(frame, cached_landmarks)

    cv2.imshow(window_name, frame)
    if cv2.waitKey(1) & 0xFF == 27 or cv2.getWindowProperty(window_name, cv2.WND_PROP_VISIBLE) < 1:
        break

cap.release()
cv2.destroyAllWindows()