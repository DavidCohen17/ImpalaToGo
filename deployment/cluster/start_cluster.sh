#!/bin/bash

# script parameters are
# param 1 - number of servers to add.
# param 2 - access key 
# param 3 - secret key
# param 4 - DNS of the master node
set +x
COUNT=$1
ACCESS_KEY=$2
SECRET_KEY=$3
MASTER_NODE=$4
S3_BUCKET=$5
if [ -z $S3_BUCKET ];
then
	echo usage:
	echo "$0 <COUNT> <ACCESS_KEY> <SECRET_KEY> <MASTER_NODE_DNS> <S3_BUCKET>"
	exit 2
fi
#TODO: Create profile with keys
#TODO: Fixup logging
BATCH_ID=$(uuidgen)
. resize.config
take_lock
echo $($LOG_PREFIX) Trying to start cluster $BATCH_ID|$LOG_APPEND
store_cluster_id $BATCH_ID
echo $($LOG_PREFIX) Creating security group if required|$LOG_APPEND
. create_impala_security_group.sh
SECURITY_GROUP_IDS=$(get_or_create_security_group ${SECURITY_GROUP}|$LOG_APPEND)
echo $($LOG_PREFIX) Checking if requested AMI: $IMAGE_ID available|$LOG_APPEND
IMAGE_AVAILABLE=$($AWS_CMD describe-images --image-ids $IMAGE_ID|$LOG_APPEND|grep -o $IMAGE_ID)
if [ -z "$IMAGE_AVAILABLE" ];
then
	echo Image: $IMAGE_ID is not available
	echo please check configuration.
	exit 1
fi
echo $($LOG_PREFIX) Requesting to start $COUNT instances of $INSTANCE_TYPE size with AMI: $IMAGE_ID|$LOG_APPEND

$AWS_CMD run-instances --image-id $IMAGE_ID --count $COUNT --instance-type $INSTANCE_TYPE --security-group-ids $SECURITY_GROUP_IDS --placement AvailabilityZone=$AVAILABILITY_ZONE --key-name $KEY_NAME --client-token $BATCH_ID --user-data "\'$USER_DATA\'" |$LOG_APPEND

echo $($LOG_PREFIX) Run-instances request sent, waiting for all instances to run|$LOG_APPEND
$AWS_CMD wait instance-running --filters Name=client-token,Values=$BATCH_ID|$LOG_APPEND

echo $($LOG_PREFIX) All instances running querying instance details |$LOG_APPEND
$AWS_CMD describe-instances --filters Name=client-token,Values=$BATCH_ID|$LOG_APPEND >$TEMP_FILE
echo $($LOG_PREFIX) Getting DNS names|$LOG_APPEND
DNS_NAMES=$(grep $BATCH_ID <${TEMP_FILE}|cut -f 14|tee ${CLUSTER_HOSTS}|$LOG_APPEND)

echo $($LOG_PREFIX) Adding ssh public-keys records to $SSH_KNOWN_HOSTS_FILE|$LOG_APPEND
CLUSTER_HOSTS_COUNT=$(cat $CLUSTER_HOSTS|wc -w)
#TODO: Add timeout
while [ $(cat $SSH_KNOWN_HOSTS_FILE|wc -l) -lt $(expr $CLUSTER_HOSTS_COUNT \* 2) ]; do
	echo $($LOG_PREFIX) Trying to perform keyscan
	ssh-keyscan -H -f $CLUSTER_HOSTS  >$SSH_KNOWN_HOSTS_FILE
        sleep 1
done
echo $($LOG_PREFIX) Keyscan complete. Added $(cat $SSH_KNOWN_HOSTS_FILE|wc -l) keys for cluster|$LOG_APPEND

echo $($LOG_PREFIX) Getting instance IDs|$LOG_APPEND
grep $BATCH_ID <${TEMP_FILE}|cut -f8|tee ${CLUSTER_VAR_DIR}/instances

./run_cmd_on_all.sh $BATCH_ID "sudo /home/ec2-user/attachToCluster.sh  $ACCESS_KEY $SECRET_KEY $MASTER_NODE $S3_BUCKET &&  sudo /home/ec2-user/restart_slave.sh" |$LOG_APPEND

rm -f $TEMP_FILE
echo $($LOG_PREFIX) All cluster nodes got configuration command. See master node $MASTER_NODE for details|$LOG_APPEND
release_lock
echo $($LOG_PREFIX) Cluster $BATCH_ID is up and running.|$LOG_APPEND
