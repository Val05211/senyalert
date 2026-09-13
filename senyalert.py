import subprocess
import sys
import time
import os

def main():
    if len(sys.argv) < 2 or sys.argv[1] != "run":
        print("Usage: python senyalert.py run")
        sys.exit(1)

    print("[SENYALERT] Starting SenyAlert Emergency Triage System...")

    # 1. Launch Python Vision Ingestion Engine
    print("[SENYALERT] Launching Python Vision Ingestion Engine...")
    src_dir = os.path.join(os.getcwd(), "src")
    python_proc = subprocess.Popen([sys.executable, "python-prototype.py"], cwd=src_dir)

    print("[SENYALERT] Giving vision engine time to initialize...")
    time.sleep(3)

    # 2. Launch Java Swing Dashboard & SQLite Backend
    print("[SENYALERT] Initializing Java WebSocket Server and SQLite Database...")
    java_dir = os.path.join(os.getcwd(), "java-dashboard")
    
    try:
        if sys.platform == "win32":
            java_proc = subprocess.Popen(
                ["mvn", "clean", "compile", "exec:java"],
                cwd=java_dir,
                shell=True
            )
        else:
            java_proc = subprocess.Popen(
                ["mvn", "clean", "compile", "exec:java"],
                cwd=java_dir
            )
        
        java_proc.wait()
    except KeyboardInterrupt:
        print("\n[SENYALERT] Shutting down system...")
    finally:
        if python_proc.poll() is None:
            python_proc.terminate()

if __name__ == "__main__":
    main()