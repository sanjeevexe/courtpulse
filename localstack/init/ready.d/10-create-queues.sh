#!/bin/sh
set -eu

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

dlq_url="$(awslocal sqs create-queue \
  --queue-name game-events-dlq.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=false,ReceiveMessageWaitTimeSeconds=2 \
  --query QueueUrl --output text)"

dlq_arn="$(awslocal sqs get-queue-attributes \
  --queue-url "$dlq_url" \
  --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)"

queue_url="$(awslocal sqs create-queue \
  --queue-name game-events.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=false \
  --query QueueUrl --output text)"

attributes="{\"VisibilityTimeout\":\"8\",\"ReceiveMessageWaitTimeSeconds\":\"2\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"

awslocal sqs set-queue-attributes \
  --queue-url "$queue_url" \
  --attributes "$attributes"

delivery_dlq_url="$(awslocal sqs create-queue \
  --queue-name alert-deliveries-dlq.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=false \
  --query QueueUrl --output text)"
delivery_dlq_arn="$(awslocal sqs get-queue-attributes \
  --queue-url "$delivery_dlq_url" --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)"
delivery_queue_url="$(awslocal sqs create-queue \
  --queue-name alert-deliveries.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=false \
  --query QueueUrl --output text)"
delivery_attributes="{\"VisibilityTimeout\":\"35\",\"ReceiveMessageWaitTimeSeconds\":\"2\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$delivery_dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"
awslocal sqs set-queue-attributes \
  --queue-url "$delivery_queue_url" --attributes "$delivery_attributes"
