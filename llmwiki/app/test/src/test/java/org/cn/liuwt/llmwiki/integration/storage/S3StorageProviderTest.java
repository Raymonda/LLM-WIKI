package org.cn.liuwt.llmwiki.integration.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3StorageProviderTest {

    @Mock
    private S3Client s3Client;

    private S3StorageProvider createProvider() {
        return new S3StorageProvider(s3Client, "llmwiki", "");
    }

    @Test
    void shouldCopyThenDeleteObjectWhenMoveCalled() {
        createProvider().move("7", "raw/.tmp/a", "raw/ab/cd/hash");

        verify(s3Client).copyObject(any(CopyObjectRequest.class));
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void shouldReturnObjectKeysWhenListCalled() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(ListObjectsV2Response.builder()
                .contents(
                    S3Object.builder().key("raw/.tmp/a").build(),
                    S3Object.builder().key("raw/.tmp/b").build())
                .build());

        List<String> keys = createProvider().list("7", "raw/.tmp");

        assertEquals(List.of("raw/.tmp/a", "raw/.tmp/b"), keys);
    }
}
