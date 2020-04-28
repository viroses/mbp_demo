import os
import json
import logging
import urllib.parse

from base64 import b64decode
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

# This is passed as a plain-text environment variable for ease of demonstration.
# Consider encrypting the value with KMS or use an encrypted parameter in Parameter Store for production deployments.
SLACK_WEBHOOK_URL = os.environ['SLACK_WEBHOOK_URL']
SLACK_CHANNEL = os.environ['SLACK_CHANNEL']

logger = logging.getLogger()
logger.setLevel(logging.INFO)

def lambda_handler(event, context):
    # print("Received event: " + json.dumps(event, indent=2))
    message = event["Records"][0]["Sns"]["Message"]
    
    data = json.loads(message)
    alarmName = data["AlarmName"]
    region = data["Region"]
    metricName = data["Trigger"]["MetricName"]
    resourceType = data["Trigger"]["Namespace"]
    resourceName = data["Trigger"]["Dimensions"][1]["value"]  + ":" + data["Trigger"]["Dimensions"][0]["value"]
    
    slack_message = {
        "channel": SLACK_CHANNEL,
        "blocks": [
        	{
        		"type": "section",
        		"text": {
        			"type": "mrkdwn",
        			"text": "Hey <@U010NSFLP9R>, you have an alarm from AWS."
        		},
                "fields": [
        			{
        				"type": "mrkdwn",
        				"text": "*Alarm name:* " + alarmName
        			},
        			{
        				"type": "mrkdwn",
        				"text": "*Region:* " + region
        			},
        			{
        				"type": "mrkdwn",
        				"text": "*Rosource name:* " + resourceName
        			},
        			{
        				"type": "mrkdwn",
        				"text": "*Resource type:* " + resourceType
        			},
        			{
        				"type": "mrkdwn",
        				"text": "*Metric name:* " + metricName
        			}
        		]
        	}
        ]
    }

    req = Request(SLACK_WEBHOOK_URL, json.dumps(slack_message).encode('utf-8'))

    response = urlopen(req)
    response.read()
    
    return None