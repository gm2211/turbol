import requests
import re
import json

url = "https://flightaware.com/live/flight/map/UAL1951/history/20230227/1309Z/KEWR/KSLC"

response = requests.get(url)

pattern = r"var trackpollBootstrap = ([\s\S]*?);</script>"
match = re.search(pattern, response.text)

if match:
    extracted_text = match.group(1)
    flight_info = json.loads(extracted_text)
    flight_id = list(flight_info["flights"].keys())[0]
    waypoints = flight_info["flights"][flight_id]["waypoints"]
    print(waypoints)
else:
    print("No match found.")