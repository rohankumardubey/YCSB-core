/*
 * Copyright (c) 2019 YCSB contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package site.ycsb.db.firestore;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteResult;
import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.DBException;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import org.apache.log4j.Logger;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/** YCSB Client for Google's Cloud Firestore. */
public class GoogleFirestoreClient extends DB {
  /** The names of properties which can be specified in the config files and flags. */
  public static final class GoogleFirestoreProperties {
    private GoogleFirestoreProperties() {}

    /** The Cloud Firestore project ID to use when running the YCSB benchmark. */
    static final String PROJECT = "googlefirestore.projectId";

    static final String PRIVKEYFILE = "googlefirestore.serviceAccountKey";
  }

  private static final Logger LOGGER = Logger.getLogger(GoogleFirestoreClient.class);

  private static final String DOCUMENT_ID = "__name__";

  private static final int PAGINATION_COUNT_PER_CALL = 1000;

  private Firestore fsDb;

  @Override
  public void init() throws DBException {
    if (fsDb != null) {
      return;
    }
    Properties properties = getProperties();
    String projectId = properties.getProperty(GoogleFirestoreProperties.PROJECT);
    if (projectId == null) {
      throw new DBException("Must provide project ID.");
    }
    String privateKeyFile = properties.getProperty(GoogleFirestoreProperties.PRIVKEYFILE);
    if (privateKeyFile == null) {
      throw new DBException("Must provide full path to private key file.");
    }
    try {
      GoogleCredentials gCreds = GoogleCredentials.fromStream(new FileInputStream(privateKeyFile));
      FirestoreOptions fsOptions = FirestoreOptions.newBuilder().setCredentials(gCreds).build();
      fsDb = fsOptions.getService();
    } catch (FileNotFoundException e) {
      throw new DBException("Can't find key.", e);
    } catch (IOException e) {
      throw new DBException("No file to import.", e);
    }

    LOGGER.info("Created Firestore client for project: " + projectId);
  }

  @Override
  public Status read(
      String table, String key, Set<String> fields, Map<String, ByteIterator> result) {
    DocumentReference docRef = toReference(table, key);

    try {
      DocumentSnapshot docSs = docRef.get().get();
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("read: Collection: " + table + " document: " + key);
      }
      if (docSs.exists()) {
        parseFields(fields, docSs, result);
        return Status.OK;
      } else {
        LOGGER.error("read: Referenced document is missing. : " + docSs.getReference().toString());
        return Status.ERROR;
      }

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted during read().", e);
      return Status.ERROR;
    } catch (ExecutionException e) {
      LOGGER.error("Error during read().", e);
      return Status.ERROR;
    }
  }

  @Override
  public Status scan(
      String table,
      String startkey,
      int recordcount,
      Set<String> fields,
      Vector<HashMap<String, ByteIterator>> result) {
    Query query = fsDb.collection(table).orderBy(DOCUMENT_ID);
    if (startkey != null) {
      query = query.startAt(startkey);
    }

    int count = 0;
    query = query.limit(PAGINATION_COUNT_PER_CALL);
    while (count <= recordcount) {
      try {
        QuerySnapshot querySs = query.get().get();
        List<QueryDocumentSnapshot> snapshots = querySs.getDocuments();

        for (QueryDocumentSnapshot docSs : querySs.getDocuments()) {
          HashMap<String, ByteIterator> scanres = new HashMap<>();
          parseFields(fields, docSs, scanres);
          result.add(scanres);
        }

        QueryDocumentSnapshot lastDoc = snapshots.get(snapshots.size() - 1);

        query = fsDb.collection(table)
            .orderBy(DOCUMENT_ID)
            .startAt(lastDoc)
            .limit(PAGINATION_COUNT_PER_CALL);

        count += PAGINATION_COUNT_PER_CALL;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.error("Interrupted during scan().", e);
        return Status.ERROR;
      } catch (ExecutionException e) {
        LOGGER.error("Error during scan().", e);
        return Status.ERROR;
      }
    }

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("scan: " + startkey + " : " + recordcount);
    }
    return Status.OK;
  }

  @Override
  public Status update(String table, String key, Map<String, ByteIterator> values) {
    DocumentReference docRef = toReference(table, key);
    Map<String, Object> data;

    try{
      if(table.equals("jobs")) {
        //When updating documents int jobs collection, only update the jobState field
        data = new HashMap<>(values.size());
        ByteIterator jobState = values.get("jobState");
        data.put("jobState", jobState.toString());
        WriteResult writeResult = docRef.update(data).get();
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("update: Update time: " + writeResult.getUpdateTime());
          LOGGER.debug("update: Document: " + key + " : " + data.toString());
        }
        return Status.OK;
      } else {
        data = toData(values);
        WriteResult writeResult = docRef.set(data, SetOptions.merge()).get();
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("update: Update time: " + writeResult.getUpdateTime());
          LOGGER.debug("update: Document: " + key + " : " + data.toString());
        }
        return Status.OK;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted during update().", e);
      return Status.ERROR;
    } catch (ExecutionException e) {
      LOGGER.error("Error during update().", e);
      return Status.ERROR;
    }
  }

  @Override
  public Status insert(String table, String key, Map<String, ByteIterator> values) {
    try {
      DocumentReference docRef = toReference(table, key);

      if(table.equals("jobs")) {
        //Inserts JobResult (in JSON) as a document into Firestore
        JSONParser parser = new JSONParser();
        Object obj = parser.parse(values.get("jobResult").toString());
        WriteResult writeResult = docRef.set(obj).get();
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("insert: Update time: " + writeResult.getUpdateTime());
        }
      } else {
        Map<String, Object> data = toData(values);
        WriteResult writeResult = docRef.set(data, SetOptions.merge()).get();
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("insert: Update time: " + writeResult.getUpdateTime());
        }
      }
      return Status.OK;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted during insert().", e);
      return Status.ERROR;
    } catch (ExecutionException | ParseException e) {
      LOGGER.error("Error during insert().", e);
      return Status.ERROR;
    }
  }

  @Override
  public Status delete(String table, String key) {
    DocumentReference docRef = toReference(table, key);

    try {
      docRef.delete().get();
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("delete: Document: " + docRef.toString());
      }
      return Status.OK;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted during delete().", e);
      return Status.ERROR;
    } catch (ExecutionException e) {
      LOGGER.error("Error during delete().", e);
      return Status.ERROR;
    }
  }

  @Override
  public Status scanWithCreatedTimeFilter(String table, String startRange, String endRange, int recordCount,
                                          Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
    Query query;
    if (startRange != null && endRange != null) {
      query = fsDb.collection(table)
          .orderBy("jobInfo.startTime")
          .whereGreaterThanOrEqualTo("jobInfo.startTime", Integer.valueOf(startRange))
          .whereLessThanOrEqualTo("jobInfo.startTime", Integer.valueOf(endRange))
          .limit(recordCount);
    } else if (startRange != null) {
      query = fsDb.collection(table)
          .orderBy("jobInfo.startTime")
          .whereGreaterThanOrEqualTo("jobInfo.startTime", Integer.valueOf(startRange))
          .limit(recordCount);
    } else if (endRange != null) {
      query = fsDb.collection(table)
          .orderBy("jobInfo.startTime")
          .whereLessThanOrEqualTo("jobInfo.startTime", Integer.valueOf(endRange))
          .limit(recordCount);
    } else {
      LOGGER.error("No valid range is provided");
      return Status.BAD_REQUEST;
    }

    return scanWithFilterHelper(query, fields, recordCount, startRange, endRange, result,
        "scanWithCreatedTimeFilter");
  }

  @Override
  public Status scanWithNamespaceKeyFilter(String table, String startKey, String endKey, int recordCount,
                                           Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
    Query query;
    if (startKey != null) {
      startKey = startKey.replace('/', '_');
    }
    if (endKey != null) {
      endKey = endKey.replace('/', '_');
    }
//    System.out.println("startKey:"+ startKey);
//    System.out.println("endKey:"+ endKey);
    if (startKey != null && endKey != null) {
      query = fsDb.collection(table)
          .orderBy(DOCUMENT_ID)
          .startAt(startKey)
          .endAt(endKey)
          .limit(recordCount);
    } else if (startKey != null) {
      query = fsDb.collection(table)
          .orderBy(DOCUMENT_ID)
          .startAt(startKey)
          .limit(recordCount);
    } else if (endKey != null) {
      query = fsDb.collection(table)
          .orderBy(DOCUMENT_ID)
          .endAt(endKey)
          .limit(recordCount);
    } else {
      LOGGER.error("No valid range is provided");
      return Status.BAD_REQUEST;
    }

    return scanWithFilterHelper(query, fields, recordCount, startKey, endKey, result,
        "scanWithNamespaceKeyFilter");
  }

  //Scan with filter only supports selecting all fields (select *)
  private Status scanWithFilterHelper(Query query, Set<String> fields, int recordCount, String startRange,
                                      String endRange, Vector<HashMap<String, ByteIterator>> result,
                                      String operationName) {
    try {
      QuerySnapshot querySs = query.get().get();
/*      System.out.println("querySs:"+ querySs);
      System.out.println("querySs.getDocuments:"+ querySs.getDocuments());
      System.out.println("querySs.getDocuments.size:"+ querySs.getDocuments().size());
      System.out.println("querySs.getQuery:"+ querySs.getQuery().toString()); */
      for (DocumentSnapshot docSs : querySs.getDocuments()) {
//        docSs.getId();
        HashMap<String, ByteIterator> scanres = new HashMap<>();
        parseFields(fields, docSs, scanres);
        result.add(scanres);
      }
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(operationName + ": " + startRange + ", " + endRange + " : " + recordCount);
      }
      return Status.OK;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("Interrupted during scan().", e);
      return Status.ERROR;
    } catch (ExecutionException e) {
      LOGGER.error("Error during scan().", e);
      return Status.ERROR;
    }
  }

  private void parseFields(
      Set<String> fields, DocumentSnapshot docSs, Map<String, ByteIterator> result) {
    Map<String, Object> data = docSs.getData();
    Set<String> docFields = fields == null ? data.keySet() : fields;
    for (String field : docFields) {
      String value = data.get(field).toString();
      result.put(field, new StringByteIterator(value));
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("parse: field: " + field + " value: " + value);
      }
    }
  }

  private DocumentReference toReference(String table, String key) {
    return fsDb.collection(table).document(key);
  }

  private Map<String, Object> toData(Map<String, ByteIterator> values) {
    Map<String, Object> data = new HashMap<>(values.size());
    for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
      data.put(entry.getKey(), entry.getValue().toString());
    }
    return data;
  }
}
