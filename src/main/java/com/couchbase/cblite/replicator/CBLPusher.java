package com.couchbase.cblite.replicator;

import com.couchbase.cblite.CBLBlobKey;
import com.couchbase.cblite.CBLBlobStore;
import com.couchbase.cblite.CBLChangeListener;
import com.couchbase.cblite.CBLDatabase;
import com.couchbase.cblite.ReplicationFilter;
import com.couchbase.cblite.CBLManager;
import com.couchbase.cblite.CBLRevisionList;
import com.couchbase.cblite.CBLiteException;
import com.couchbase.cblite.internal.CBLRevisionInternal;
import com.couchbase.cblite.internal.InterfaceAudience;
import com.couchbase.cblite.support.CBLRemoteRequestCompletionBlock;
import com.couchbase.cblite.support.HttpClientFactory;
import com.couchbase.cblite.util.Log;

import org.apache.http.client.HttpResponseException;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

public class CBLPusher extends CBLReplicator implements CBLChangeListener {


    private boolean createTarget;
    private boolean observing;
    private ReplicationFilter filter;

    /**
     * Constructor
     */
    @InterfaceAudience.Private
    public CBLPusher(CBLDatabase db, URL remote, boolean continuous, ScheduledExecutorService workExecutor) {
        this(db, remote, continuous, null, workExecutor);
    }

    /**
     * Constructor
     */
    @InterfaceAudience.Private
    public CBLPusher(CBLDatabase db, URL remote, boolean continuous, HttpClientFactory clientFactory, ScheduledExecutorService workExecutor) {
        super(db, remote, continuous, clientFactory, workExecutor);
        createTarget = false;
        observing = false;
    }

    @Override
    @InterfaceAudience.Public
    public boolean isPull() {
        return false;
    }

    @Override
    @InterfaceAudience.Public
    public boolean isCreateTarget() {
        // TODO: make this actually do something
        return createTarget;
    }


    public void setCreateTarget(boolean createTarget) {
        this.createTarget = createTarget;
    }

    public void setFilter(ReplicationFilter filter) {
        this.filter = filter;
    }


    @Override
    public void maybeCreateRemoteDB() {
        if(!createTarget) {
            return;
        }
        Log.v(CBLDatabase.TAG, "Remote db might not exist; creating it...");
        sendAsyncRequest("PUT", "", null, new CBLRemoteRequestCompletionBlock() {

            @Override
            public void onCompletion(Object result, Throwable e) {
                if(e != null && e instanceof HttpResponseException && ((HttpResponseException)e).getStatusCode() != 412) {
                    Log.v(CBLDatabase.TAG, "Unable to create remote db (normal if using sync gateway)");
                } else {
                    Log.v(CBLDatabase.TAG, "Created remote db");

                }
                createTarget = false;
                beginReplicating();
            }

        });
    }

    @Override
    public void beginReplicating() {
        // If we're still waiting to create the remote db, do nothing now. (This method will be
        // re-invoked after that request finishes; see maybeCreateRemoteDB() above.)
        if(createTarget) {
            return;
        }

        if(filterName != null) {
            filter = db.getFilter(filterName);
        }
        if(filterName != null && filter == null) {
            Log.w(CBLDatabase.TAG, String.format("%s: No ReplicationFilter registered for filter '%s'; ignoring", this, filterName));;
        }

        // Process existing changes since the last push:
        long lastSequenceLong = 0;
        if(lastSequence != null) {
            lastSequenceLong = Long.parseLong(lastSequence);
        }
        CBLRevisionList changes = db.changesSince(lastSequenceLong, null, filter);
        if(changes.size() > 0) {
            processInbox(changes);
        }

        // Now listen for future changes (in continuous mode):
        if(continuous) {
            observing = true;
            db.addChangeListener(this);
            asyncTaskStarted();  // prevents stopped() from being called when other tasks finish
        }
    }

    @Override
    public void stop() {
        stopObserving();
        super.stop();
    }

    private void stopObserving() {
        if(observing) {
            observing = false;
            db.removeChangeListener(this);
            asyncTaskFinished(1);
        }
    }

    @Override
    public void onDatabaseChanged(CBLDatabase database, Map<String, Object> changeNotification) {
        // Skip revisions that originally came from the database I'm syncing to:
        URL source = (URL)changeNotification.get("source");
        if(source != null && source.equals(remote.toExternalForm())) {
            return;
        }
        CBLRevisionInternal rev = (CBLRevisionInternal)changeNotification.get("rev");
        if(rev != null && ((filter == null) || filter.filter(rev, null))) {
            addToInbox(rev);
        }
    }

    @Override
    public void onFailureDatabaseChanged(Throwable exception) {
        Log.e(CBLDatabase.TAG, "onFailureDatabaseChanged", exception);
    }

    @Override
    public void processInbox(final CBLRevisionList inbox) {
        final long lastInboxSequence = inbox.get(inbox.size()-1).getSequence();
        // Generate a set of doc/rev IDs in the JSON format that _revs_diff wants:
        Map<String,List<String>> diffs = new HashMap<String,List<String>>();
        for (CBLRevisionInternal rev : inbox) {
            String docID = rev.getDocId();
            List<String> revs = diffs.get(docID);
            if(revs == null) {
                revs = new ArrayList<String>();
                diffs.put(docID, revs);
            }
            revs.add(rev.getRevId());
        }

        // Call _revs_diff on the target db:
        asyncTaskStarted();
        sendAsyncRequest("POST", "/_revs_diff", diffs, new CBLRemoteRequestCompletionBlock() {

            @Override
            public void onCompletion(Object response, Throwable e) {


                Map<String,Object> results = (Map<String,Object>)response;
                if(e != null) {
                    error = e;
                    stop();
                } else if(results.size() != 0) {
                    // Go through the list of local changes again, selecting the ones the destination server
                    // said were missing and mapping them to a JSON dictionary in the form _bulk_docs wants:
                    List<Object> docsToSend = new ArrayList<Object>();
                    for(CBLRevisionInternal rev : inbox) {
                        Map<String,Object> properties = null;
                        Map<String,Object> resultDoc = (Map<String,Object>)results.get(rev.getDocId());
                        if(resultDoc != null) {
                            List<String> revs = (List<String>)resultDoc.get("missing");
                            if(revs != null && revs.contains(rev.getRevId())) {
                                //remote server needs this revision
                                // Get the revision's properties
                                if(rev.isDeleted()) {
                                    properties = new HashMap<String,Object>();
                                    properties.put("_id", rev.getDocId());
                                    properties.put("_rev", rev.getRevId());
                                    properties.put("_deleted", true);
                                } else {
                                    // OPT: Shouldn't include all attachment bodies, just ones that have changed
                                    EnumSet<CBLDatabase.TDContentOptions> contentOptions = EnumSet.of(
                                            CBLDatabase.TDContentOptions.TDIncludeAttachments,
                                            CBLDatabase.TDContentOptions.TDBigAttachmentsFollow
                                    );

                                    try {
                                        db.loadRevisionBody(rev, contentOptions);
                                    } catch (CBLiteException e1) {
                                        throw new RuntimeException(e1);
                                    }
                                    properties = new HashMap<String,Object>(rev.getProperties());

                                }
                                if (properties.containsKey("_attachments")) {
                                    if (uploadMultipartRevision(rev)) {
                                        continue;
                                    }
                                }
                                if(properties != null) {
                                    // Add the _revisions list:
                                    properties.put("_revisions", db.getRevisionHistoryDict(rev));
                                    //now add it to the docs to send
                                    docsToSend.add(properties);
                                }
                            }
                        }
                    }

                    // Post the revisions to the destination. "new_edits":false means that the server should
                    // use the given _rev IDs instead of making up new ones.
                    final int numDocsToSend = docsToSend.size();
                    Map<String,Object> bulkDocsBody = new HashMap<String,Object>();
                    bulkDocsBody.put("docs", docsToSend);
                    bulkDocsBody.put("new_edits", false);
                    Log.i(CBLDatabase.TAG, String.format("%s: Sending %d revisions", this, numDocsToSend));
                    Log.v(CBLDatabase.TAG, String.format("%s: Sending %s", this, inbox));
                    setChangesTotal(getChangesTotal() + numDocsToSend);
                    asyncTaskStarted();
                    sendAsyncRequest("POST", "/_bulk_docs", bulkDocsBody, new CBLRemoteRequestCompletionBlock() {

                        @Override
                        public void onCompletion(Object result, Throwable e) {
                            if(e != null) {
                                error = e;
                            } else {
                                Log.v(CBLDatabase.TAG, String.format("%s: Sent %s", this, inbox));
                                setLastSequence(String.format("%d", lastInboxSequence));
                            }
                            setChangesProcessed(getChangesProcessed() + numDocsToSend);
                            asyncTaskFinished(1);
                        }
                    });

                } else {
                    // If none of the revisions are new to the remote, just bump the lastSequence:
                    setLastSequence(String.format("%d", lastInboxSequence));
                }
                asyncTaskFinished(1);
            }

        });
    }

    private boolean uploadMultipartRevision(CBLRevisionInternal revision) {

        MultipartEntity multiPart = null;

        Map<String, Object> revProps = revision.getProperties();
        revProps.put("_revisions", db.getRevisionHistoryDict(revision));

        Map<String, Object> attachments = (Map<String, Object>) revProps.get("_attachments");
        for (String attachmentKey : attachments.keySet()) {
            Map<String, Object> attachment = (Map<String, Object>) attachments.get(attachmentKey);
            if (attachment.containsKey("follows")) {

                if (multiPart == null) {

                    multiPart = new MultipartEntity();

                    try {
                        String json  = CBLManager.getObjectMapper().writeValueAsString(revProps);
                        Charset utf8charset = Charset.forName("UTF-8");
                        multiPart.addPart("param1", new StringBody(json, "application/json", utf8charset));

                    } catch (IOException e) {
                        throw new IllegalArgumentException(e);
                    }

                }

                CBLBlobStore blobStore = this.db.getAttachments();
                String base64Digest = (String) attachment.get("digest");
                CBLBlobKey blobKey = new CBLBlobKey(base64Digest);
                InputStream inputStream = blobStore.blobStreamForKey(blobKey);
                if (inputStream == null) {
                    Log.w(CBLDatabase.TAG, "Unable to find blob file for blobKey: " + blobKey + " - Skipping upload of multipart revision.");
                    multiPart = null;
                }
                else {
                    String contentType = null;
                    if (attachment.containsKey("content_type")) {
                        contentType = (String) attachment.get("content_type");
                    }
                    else if (attachment.containsKey("content-type")) {
                        String message = String.format("Found attachment that uses content-type" +
                                " field name instead of content_type (see couchbase-lite-android" +
                                " issue #80): " + attachment);
                        Log.w(CBLDatabase.TAG, message);
                    }
                    multiPart.addPart(attachmentKey, new InputStreamBody(inputStream, contentType, attachmentKey));
                }

            }
        }

        if (multiPart == null) {
            return false;
        }

        String path = String.format("/%s?new_edits=false", revision.getDocId());

        // TODO: need to throttle these requests
        Log.d(CBLDatabase.TAG, "Uploadeding multipart request.  Revision: " + revision);
        asyncTaskStarted();
        sendAsyncMultipartRequest("PUT", path, multiPart, new CBLRemoteRequestCompletionBlock() {
            @Override
            public void onCompletion(Object result, Throwable e) {
                if(e != null) {
                    Log.e(CBLDatabase.TAG, "Exception uploading multipart request", e);
                    error = e;
                } else {
                    Log.d(CBLDatabase.TAG, "Uploaded multipart request.  Result: " + result);
                }
                asyncTaskFinished(1);
            }
        });

        return true;

    }

}
