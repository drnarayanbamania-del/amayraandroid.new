#!/system/bin/sh
# Create a contact "Priya Sharma" with mobile number +919876543210, then verify.
content insert --uri content://com.android.contacts/raw_contacts
RID=$(content query --uri content://com.android.contacts/raw_contacts --projection _id | tail -1 | sed 's/.*_id=//' | tr -d '\r')
echo "RID=$RID"
content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RID --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"Priya Sharma"
content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RID --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:+919876543210 --bind data2:i:2
echo "--- contacts now:"
content query --uri content://com.android.contacts/contacts --projection _id:display_name
