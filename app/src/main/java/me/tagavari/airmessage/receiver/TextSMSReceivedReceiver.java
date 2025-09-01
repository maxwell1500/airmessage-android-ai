package me.tagavari.airmessage.receiver;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleEmitter;
import io.reactivex.rxjava3.schedulers.Schedulers;
import kotlin.Triple;
import me.tagavari.airmessage.enums.MessageSendErrorCode;
import me.tagavari.airmessage.enums.MessageState;
import me.tagavari.airmessage.helper.AddressHelper;
import me.tagavari.airmessage.helper.MMSSMSHelper;
import me.tagavari.airmessage.helper.TwoFACodeManager;
import me.tagavari.airmessage.messaging.ConversationInfo;
import me.tagavari.airmessage.messaging.MessageInfo;

import java.util.ArrayList;
import java.util.Collections;

public class TextSMSReceivedReceiver extends BroadcastReceiver {
	private static final String TAG = "TextSMSReceiver";
	
	@Override
	public void onReceive(Context context, Intent intent) {
		Log.d(TAG, "SMS received - processing...");
		Bundle bundle = intent.getExtras();
		Object[] pdus = (Object[]) bundle.get("pdus");
		String format = bundle.getString("format");
		
		//Getting the message
		StringBuilder messageBody = new StringBuilder();
		String messageSender = null;
		//long messageTimestamp;
		for(Object o : pdus) {
			//Getting the SMS message
			SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) o, format);
			
			//Appending the information
			messageBody.append(smsMessage.getMessageBody());
			if(smsMessage.getOriginatingAddress() != null) {
				messageSender = AddressHelper.normalizeAddress(smsMessage.getOriginatingAddress());
			}
			//timestamp = smsMessage.getTimestampMillis();
		}
		long timestamp = System.currentTimeMillis();
		
		if(messageSender == null) {
			Log.w(TAG, "No sender found, skipping message");
			return;
		}
		String finalMessageSender = messageSender;
		
		Log.d(TAG, "SMS from: '" + finalMessageSender + "' (length=" + finalMessageSender.length() + ")");
		Log.d(TAG, "SMS text: '" + messageBody.toString() + "'");
		
		//Running on a worker thread
		Single.create((SingleEmitter<Triple<Boolean, ConversationInfo, MessageInfo>> emitter) -> {
			//Writing the message to Android's database
			insertInternalSMS(context, finalMessageSender, messageBody.toString(), timestamp);
		}).subscribeOn(Schedulers.single()).subscribe();
		
		// Check if this should be handled as a 2FA message
		String messageText = messageBody.toString();
		boolean senderLengthCheck = finalMessageSender.length() <= 7;
		boolean contentCheck = TwoFACodeManager.INSTANCE.shouldProcessAs2FA(messageText);
		boolean shouldHandle2FA = senderLengthCheck && contentCheck;
		
		Log.d(TAG, "2FA Analysis:");
		Log.d(TAG, "  - Sender length check (≤7): " + senderLengthCheck + " (" + finalMessageSender.length() + " chars)");
		Log.d(TAG, "  - Content check: " + contentCheck);
		Log.d(TAG, "  - Should handle as 2FA: " + shouldHandle2FA);
		
		if (shouldHandle2FA) {
			Log.i(TAG, "Processing SMS as 2FA message");
			// Process as 2FA message - this will store it in the 2FA database if it contains codes
			MessageInfo messageInfo = new MessageInfo(-1, -1, null, timestamp, finalMessageSender, messageText, null, new ArrayList<>(), null, false, -1, MessageState.sent, MessageSendErrorCode.none, false, null);
			TwoFACodeManager.INSTANCE.processMessage(context, messageInfo)
				.subscribeOn(Schedulers.io())
				.subscribe(
					wasProcessed -> {
						Log.d(TAG, "2FA processing result: " + wasProcessed);
						if (!wasProcessed) {
							Log.d(TAG, "2FA processing failed, falling back to regular message");
							MMSSMSHelper.updateTextConversationMessage(context, Collections.singletonList(finalMessageSender), messageInfo).subscribe();
						} else {
							Log.i(TAG, "Successfully processed as 2FA message!");
						}
					},
					error -> {
						Log.e(TAG, "2FA processing error, falling back to regular message", error);
						MMSSMSHelper.updateTextConversationMessage(context, Collections.singletonList(finalMessageSender), messageInfo).subscribe();
					}
				);
		} else {
			Log.i(TAG, "Processing SMS as regular message");
			MMSSMSHelper.updateTextConversationMessage(context, Collections.singletonList(finalMessageSender), new MessageInfo(-1, -1, null, timestamp, finalMessageSender, messageText, null, new ArrayList<>(), null, false, -1, MessageState.sent, MessageSendErrorCode.none, false, null)).subscribe();
		}
	}
	
	/**
	 * Inserts a message into Android's internal SMS database
	 * @param context The context to use
	 * @param sender The sender of the message
	 * @param body The text content of the message
	 * @param timestamp The date the message was sent
	 */
	private static void insertInternalSMS(Context context, String sender, String body, long timestamp) {
		ContentValues contentValues = new ContentValues();
		contentValues.put(Telephony.Sms.ADDRESS, sender);
		contentValues.put(Telephony.Sms.BODY, body);
		contentValues.put(Telephony.Sms.DATE, System.currentTimeMillis());
		contentValues.put(Telephony.Sms.READ, "1");
		contentValues.put(Telephony.Sms.DATE_SENT, timestamp);
		
		try {
			context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, contentValues);
		} catch(Exception exception) {
			exception.printStackTrace();
		}
	}
}