package com.cloud.lambda;

import java.time.Instant;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.PutItemOutcome;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;

public class ResetPassword implements RequestHandler<SNSEvent, Object> {

	AmazonDynamoDB dynamodbClient;
	AmazonSimpleEmailService sesClient;

	@Override
	public Object handleRequest(SNSEvent snsEvent, Context context) {
		LambdaLogger logger = context.getLogger();
		try {
			logger.log("Lambda function triggered");

			dynamodbClient = AmazonDynamoDBClientBuilder.defaultClient();
			logger.log("DynamoDbClinet built successfully");
			
			sesClient = AmazonSimpleEmailServiceClientBuilder.defaultClient();
			logger.log("SESClient built successfully");

			DynamoDB dynamodb = new DynamoDB(dynamodbClient);
			logger.log("DynamoDB built successfully");
			
			Table table = dynamodb.getTable(System.getenv("DYNAMODB_TABLE_NAME"));
			logger.log("DynamoDB Table fetched successfully");

			for (SNSEvent.SNSRecord snsRecord : snsEvent.getRecords()) {
				logger.log("Inside sns records");
				SNSEvent.SNS sns = snsRecord.getSNS();
				String email = sns.getMessage();
				logger.log("Email is: "+email);
				
				Instant currentInstant = Instant.now();
				Long currentTTL = currentInstant.getEpochSecond();
				
				
				QuerySpec spec = new QuerySpec()
						.withKeyConditionExpression("username = :v_email")
						.withFilterExpression("timestamp_ttl > :v_ttl")
						.withValueMap(new ValueMap()
								.withString(":v_email", email)
								.withString(":v_ttl", currentTTL.toString()));
				
				logger.log(spec.toString());
				
				ItemCollection<QueryOutcome> items = table.query(spec);
				logger.log(items.toString());
				
				Iterator<Item> iterator = items.iterator();
				
				if(!iterator.hasNext()) {
					logger.log("No token found for email: " + email);
					logger.log("Generating new token for email: " + email);
					
					currentInstant = Instant.now();
					Instant expirationInstant = currentInstant.plusSeconds(TimeUnit.MINUTES.toSeconds(Long.parseLong(System.getenv("TTL"))));
					Long expirationTTL = expirationInstant.getEpochSecond();
					
					String token = UUID.randomUUID().toString();
					String domain = System.getenv("DOMAIN_NAME");
					String fromEmail = "no-reply@" + domain;
					logger.log("MAIL FROM: " + fromEmail);
					String resetLink = "http://" + domain + "?email=" + email + "&token=" + token;
					logger.log(resetLink);
					
					final String SUBJECT = "Password Reset Link";
					final String HTMLBODY = "<h1>CSYE 6225 Password Reset Link</h1>"
							+ "<p> Here is your password reset link.</p> "
							+ "<a>" + resetLink + "</a>"
							+ "<p>Please note that this link will be active only for 20 mins from the time you receive this email!</p>";

					Item item = new Item()
							.withPrimaryKey("username", email)
							.with("timestamp_ttl", expirationTTL.toString())
							.with("token", resetLink);
					PutItemOutcome outcome = table.putItem(item);
					logger.log("Token generated successfully for email: " + email);
					
					logger.log("Creating SES email request");
					SendEmailRequest emailRequest = new SendEmailRequest()
							.withDestination(new Destination().withToAddresses(email))
							.withMessage(new Message()
									.withBody(new Body()
											.withHtml(new Content()
													.withCharset("UTF-8").withData(HTMLBODY))
											.withText(new Content()
													.withCharset("UTF-8").withData(resetLink)))
									.withSubject(new Content()
											.withCharset("UTF-8").withData(SUBJECT)))
							.withSource(fromEmail);
					sesClient.sendEmail(emailRequest);
					logger.log("Password reset email sent successfully to email: "+ email);
				} else {
					logger.log("User: "+ email + " already has an active token");
				}
			}
		} catch (Exception e) {
			logger.log("Error: " + e.getMessage());
			logger.log("StackTrace Line: " + e.getStackTrace()[e.getStackTrace().length-1].getLineNumber());
			logger.log("StackTrace File: " + e.getStackTrace()[e.getStackTrace().length-1].getFileName());
			logger.log(e.getStackTrace().toString());
		}
		return null;
	}

}
