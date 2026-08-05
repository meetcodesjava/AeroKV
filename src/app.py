import time
import socket

class AeroKVClient:
    def __init__(self, host="localhost", port=8080):
        self.host=host
        self.port=port

    def send_command(self, command_string):
        """Opens a network pipe, sends the command and reads the response"""
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.connect((self.host, self.port))

                full_command=command_string + "\n"
                s.sendall(full_command.encode('utf-8'))

                response=s.recv(1024).decode('utf-8').strip()
                return response
        except Exception as e:
            return f"ERR_CONNECTION_FAILED:{e}"
        

if __name__=="__main__":
    db=AeroKVClient(host="localhost", port=8080)
    print("Python web app initialized. Connecting to AeroKV storage...")


    print("\n[Web App Application]: User logs in. Saving session to cache...")
    result=db.send_command("SET, session_369, user_profile_data, 5000")
    print(f"[AeroKV Response]: {result}")

    print("\n[Web App Application]: User loads Dashboard. Fetching session...")
    profile=db.send_command("GET, session_369")
    print(f"[AeroKV Response]: {profile}")

    print("\n simulating 6 seconds of user inactivity...")
    time.sleep(6)

    print("\n[Web App Application]: User clicks 'Settings. Validating session...")
    profile_after_delay=db.send_command("GET, session_369")
    print(f"\n[AeroKV Response]: {profile_after_delay}")