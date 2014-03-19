package org.apache.hadoop.fs.azurenative;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import java.net.*;
import java.io.*;
import java.util.*;

import org.apache.hadoop.conf.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.azure.AzureException;
import org.apache.hadoop.fs.azurenative.AzureNativeFileSystemStore.TestHookOperationContext;
import org.junit.*;

import com.microsoft.windowsazure.storage.*;


public class TestAzureFileSystemErrorConditions {
  private static final int ALL_THREE_FILE_SIZE = 1024;

  @Test
  public void testNoInitialize() throws Exception {
    AzureNativeFileSystemStore store = new AzureNativeFileSystemStore();
    boolean passed = false;
    try {
      store.retrieveMetadata("foo");
      passed = true;
    } catch (AssertionError e) {
    }
    assertFalse(
        "Doing an operation on the store should throw if not initalized.",
        passed);
  }

  /**
   * Try accessing an unauthorized or non-existent (treated the same)
   * container from WASB.
   */
  @Test
  public void testAccessUnauthorizedPublicContainer() throws Exception {
    Path noAccessPath = new Path(
        "wasb://nonExistentContainer@hopefullyNonExistentAccount/someFile");
    NativeAzureFileSystem.suppressRetryPolicy();
    try {
      FileSystem.get(noAccessPath.toUri(), new Configuration())
        .open(noAccessPath);
      assertTrue("Should've thrown.", false);
    } catch (AzureException ex) {
      assertTrue("Unexpected message in exception " + ex,
          ex.getMessage().contains(
          "Unable to access container nonExistentContainer in account" +
          " hopefullyNonExistentAccount"));
    } finally {
      NativeAzureFileSystem.resumeRetryPolicy();
    }
  }

  @Test
  public void testAccessContainerWithWrongVersion() throws Exception {
    AzureNativeFileSystemStore store = new AzureNativeFileSystemStore();
    MockStorageInterface mockStorage = new MockStorageInterface();
    store.setAzureStorageInteractionLayer(mockStorage);
    FileSystem fs = new NativeAzureFileSystem(store);
    try {
      Configuration conf = new Configuration();
      AzureBlobStorageTestAccount.setMockAccountKey(conf);
      HashMap<String, String> metadata = new HashMap<String, String>();
      metadata.put(AzureNativeFileSystemStore.VERSION_METADATA_KEY,
          "2090-04-05"); // It's from the future!
      mockStorage.addPreExistingContainer(
          AzureBlobStorageTestAccount.getMockContainerUri(),
          metadata);
  
      boolean passed = false;
      try {
        fs.initialize(new URI(AzureBlobStorageTestAccount.MOCK_WASB_URI), conf);
        fs.listStatus(new Path("/"));
        passed = true;
      } catch (AzureException ex) {
        assertTrue("Unexpected exception message: " + ex,
            ex.getMessage().contains("unsupported version: 2090-04-05."));
      }
      assertFalse("Should've thrown an exception because of the wrong version.",
          passed);
    } finally {
      fs.close();
    }
  }

  private interface ConnectionRecognizer {
    boolean isTargetConnection(HttpURLConnection connection);
  }

  private class TransientErrorInjector extends StorageEvent<SendingRequestEvent> {
    final ConnectionRecognizer connectionRecognizer;
    private boolean injectedErrorOnce = false;

    public TransientErrorInjector(ConnectionRecognizer connectionRecognizer) {
      this.connectionRecognizer = connectionRecognizer;
    }

    @Override
    public void eventOccurred(SendingRequestEvent eventArg) {
      HttpURLConnection connection = (HttpURLConnection)eventArg.getConnectionObject();
      if (!connectionRecognizer.isTargetConnection(connection)) {
        return;
      }
      if (!injectedErrorOnce) {
        connection.setReadTimeout(1);
        connection.disconnect();
        injectedErrorOnce = true;
      }
    }
  }

  private void injectTransientError(NativeAzureFileSystem fs,
      final ConnectionRecognizer connectionRecognizer) {
    fs.getStore().addTestHookToOperationContext(new TestHookOperationContext() {
      @Override
      public OperationContext modifyOperationContext(OperationContext original) {
        original.getSendingRequestEventHandler().addListener(
            new TransientErrorInjector(connectionRecognizer));
        return original;
      }
    });
  }

  @Test
  public void testTransientErrorOnDelete() throws Exception {
    // Need to do this test against a live storage account
    AzureBlobStorageTestAccount testAccount =
        AzureBlobStorageTestAccount.create();
    assumeNotNull(testAccount);
    try {
      NativeAzureFileSystem fs = testAccount.getFileSystem();
      injectTransientError(fs, new ConnectionRecognizer() {
        @Override
        public boolean isTargetConnection(HttpURLConnection connection) {
          return connection.getRequestMethod().equals("DELETE");
        }
      });
      Path testFile = new Path("/a/b");
      assertTrue(fs.createNewFile(testFile));
      assertTrue(fs.rename(testFile, new Path("/x")));
    } finally {
      testAccount.cleanup();
    }
  }

  private void writeAllThreeFile(NativeAzureFileSystem fs, Path testFile)
      throws IOException {
    byte[] buffer = new byte[ALL_THREE_FILE_SIZE];
    Arrays.fill(buffer, (byte)3);
    OutputStream stream = fs.create(testFile);
    stream.write(buffer);
    stream.close();
  }

  private void readAllThreeFile(NativeAzureFileSystem fs, Path testFile)
      throws IOException {
    byte[] buffer = new byte[ALL_THREE_FILE_SIZE];
    InputStream inStream = fs.open(testFile);
    assertEquals(buffer.length,
        inStream.read(buffer, 0, buffer.length));
    inStream.close();
    for (int i = 0; i < buffer.length; i++) {
      assertEquals(3, buffer[i]);
    }
  }

  @Test
  public void testTransientErrorOnCommitBlockList() throws Exception {
    // Need to do this test against a live storage account
    AzureBlobStorageTestAccount testAccount =
        AzureBlobStorageTestAccount.create();
    assumeNotNull(testAccount);
    try {
      NativeAzureFileSystem fs = testAccount.getFileSystem();
      injectTransientError(fs, new ConnectionRecognizer() {
        @Override
        public boolean isTargetConnection(HttpURLConnection connection) {
          return connection.getRequestMethod().equals("PUT") &&
              connection.getURL().getQuery().contains("blocklist");
        }
      });
      Path testFile = new Path("/a/b");
      writeAllThreeFile(fs, testFile);
      readAllThreeFile(fs, testFile);
    } finally {
      testAccount.cleanup();
    }
  }

  @Test
  public void testTransientErrorOnRead() throws Exception {
    // Need to do this test against a live storage account
    AzureBlobStorageTestAccount testAccount =
        AzureBlobStorageTestAccount.create();
    assumeNotNull(testAccount);
    try {
      NativeAzureFileSystem fs = testAccount.getFileSystem();
      Path testFile = new Path("/a/b");
      writeAllThreeFile(fs, testFile);
      injectTransientError(fs, new ConnectionRecognizer() {
        @Override
        public boolean isTargetConnection(HttpURLConnection connection) {
          return connection.getRequestMethod().equals("GET");
        }
      });
      readAllThreeFile(fs, testFile);
    } finally {
      testAccount.cleanup();
    }
  }
}
