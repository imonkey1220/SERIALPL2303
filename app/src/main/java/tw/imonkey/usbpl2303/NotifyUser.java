package tw.imonkey.usbpl2303;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
class NotifyUser {
  static int limit=1000;
    static void SMSPUSH( String deviceId ,String memberEmail,String message){
        DatabaseReference mSMS= FirebaseDatabase.getInstance().getReference("/LOG/SMS/");
        Map<String, Object> SMS = new HashMap<>();
        SMS.clear();
        SMS.put("message",message);
        SMS.put("deviceId",deviceId);
        SMS.put("memberEmail",memberEmail);
        SMS.put("timeStamp", ServerValue.TIMESTAMP);
        mSMS.push().setValue(SMS);
        dataLimit(mSMS,limit);
    }

    static void emailPUSH( String deviceId ,String memberEmail,String message ){
        DatabaseReference mEMAIL= FirebaseDatabase.getInstance().getReference("/LOG/EMAIL/");
        Map<String, Object> EMAIL = new HashMap<>();
        EMAIL.clear();
        EMAIL.put("message",message);
        EMAIL.put("deviceId",deviceId);
        EMAIL.put("memberEmail",memberEmail);
        EMAIL.put("timeStamp", ServerValue.TIMESTAMP);
        mEMAIL.push().setValue(EMAIL);
        dataLimit(mEMAIL,limit);

    }

    static void topicsPUSH( String deviceId ,String memberEmail,String message_title,String message_body){
        DatabaseReference mTopicsPUSH= FirebaseDatabase.getInstance().getReference("/LOG/PUSHTopics/");

        Map<String, Object> message = new HashMap<>();
        message.clear();
        message.put("title",message_title);
        message.put("body",message_body);

        Map<String, Object> PUSH = new HashMap<>();
        PUSH.clear();
        PUSH.put("message",message);
        PUSH.put("deviceId",deviceId);
        PUSH.put("memberEmail",memberEmail);
        PUSH.put("timeStamp", ServerValue.TIMESTAMP);
        mTopicsPUSH.push().setValue(PUSH);
        dataLimit(mTopicsPUSH,limit);
    }

    static void IIDPUSH(String deviceId,String memberEmail,String message_title,String message_body){
        DatabaseReference mIIDPUSH= FirebaseDatabase.getInstance().getReference("/LOG/PUSHIID/");

        Map<String, Object> message = new HashMap<>();
        message.clear();
        message.put("title",message_title);
        message.put("body",message_body);

        Map<String, Object> PUSH = new HashMap<>();
        PUSH.clear();
        PUSH.put("message",message);
        PUSH.put("deviceId",deviceId);
        PUSH.put("memberEmail",memberEmail);
        PUSH.put("timeStamp", ServerValue.TIMESTAMP);
        mIIDPUSH.push().setValue(PUSH);
        dataLimit(mIIDPUSH,limit);
    }

    private static void dataLimit(final DatabaseReference mData,final int limit) {
        mData.addListenerForSingleValueEvent(new ValueEventListener() {
            int dataCount ;
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                dataCount=(int)(snapshot.getChildrenCount());
                if((dataCount-limit)>0) {
                    mData.orderByKey().limitToFirst(dataCount - limit)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(DataSnapshot snapshot) {
                                    for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                                        mData.child(childSnapshot.getKey()).removeValue();
                                    }
                                }

                                @Override
                                public void onCancelled(DatabaseError databaseError) {
                                }
                            });
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });
    }
}


