import os
import json
import logging
import urllib.parse
import boto3
import botocore
import time

from base64 import b64decode
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError
from urllib import parse

def lambda_handler(event, context):
    # TODO implement
    # print("Received event: " + json.dumps(event, indent=2))
    
    # Parsing Slack message
    slackBody = event["body"]
    
    messages = {}
    for message in slackBody.split('&'):
        messages[str(message.split('=')[0])]=str(message.split('=')[1])
    
    slackCommand = messages.get('command', 'command')
    slackText = str(messages.get('text', 'text')).replace('+',' ')
    responseURL = parse.unquote(messages.get('response_url','response_url'))
    userID = messages.get('user_id','user_id')
    
    # Write AWS commands
    rds = boto3.client('rds',region_name='ap-northeast-2')
    
    print(slackText)

    if(slackCommand == '%2Fchatops'):
        if(slackText == 'aurora-mysql instance count'):
            boto_response = rds.describe_db_clusters(DBClusterIdentifier='aurora-mysql')
            db_instances = boto_response['DBClusters'][0]["DBClusterMembers"]
            db_instance_name = []
            
            for instance in db_instances:
                db_instance_name.append(f'{instance["DBInstanceIdentifier"]}')
            
            slaves = ''
            for name in db_instance_name:
                slaves += name + ' '
            
            slack_message = {
                "response_type": "in_channel",
                "text": "<@" + userID + ">, " + str(boto_response['DBClusters'][0]["DBClusterIdentifier"]) + " has " + str(len(db_instance_name)) + " instance(s): " + slaves
            }
            # Return to Slack
            headers = {'Content-type': 'application/json'}
            req = Request(responseURL, json.dumps(slack_message).encode('utf-8'), headers)
            response = urlopen(req)
            print(response.read())
            
        elif(slackText == 'add 2 aurora-mysql slave'):
            boto_response = rds.describe_db_clusters(DBClusterIdentifier='aurora-mysql')
            source_db_instance = boto_response['DBClusters'][0]["DBClusterMembers"][0]["DBInstanceIdentifier"]
            boto_response = rds.describe_db_instances(DBInstanceIdentifier=source_db_instance)
            target_db_engine = boto_response['DBInstances'][0]["Engine"]
            target_db_instance_class = boto_response['DBInstances'][0]["DBInstanceClass"]
            target_db_az = boto_response['DBInstances'][0]["AvailabilityZone"]
            
            for i in range(0,2):
                response = rds.create_db_instance(
                    DBClusterIdentifier='aurora-mysql',
                    DBInstanceIdentifier='temp-instance-' + str(int(time.time())),
                    Engine=target_db_engine,
                    DBInstanceClass=target_db_instance_class,
                    AvailabilityZone=target_db_az
                )
            
            slack_message = {
                "response_type": "in_channel",
                "text": "<@" + userID + ">,\nyour request was initiated.\nIt would take few minutes."
            }
            
            # Return to Slack
            headers = {'Content-type': 'application/json'}
            req = Request(responseURL, json.dumps(slack_message).encode('utf-8'), headers)
            response = urlopen(req)
            print(response.read())
    else:
        print("this command is not /chatops")
    
    return None