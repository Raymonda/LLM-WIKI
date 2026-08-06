package org.cn.liuwt.llmwiki.integration.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.URI;

@Component
@ConditionalOnProperty(name = "llmwiki.storage.provider", havingValue = "s3")
public class S3StorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(S3StorageProvider.class);

    private final S3Client s3Client;

    @Value("${llmwiki.storage.s3.bucket-prefix:llmwiki}")
    private String bucketPrefix;

    @Value("${llmwiki.storage.s3.public-url:}")
    private String publicUrl;

    public S3StorageProvider(
            @Value("${llmwiki.storage.s3.endpoint}") String endpoint,
            @Value("${llmwiki.storage.s3.access-key}") String accessKey,
            @Value("${llmwiki.storage.s3.secret-key}") String secretKey,
            @Value("${llmwiki.storage.s3.region:us-east-1}") String region,
            @Value("${llmwiki.storage.s3.path-style:false}") boolean pathStyle
    ) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(region));
        if (endpoint != null && !endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (pathStyle) {
            builder.forcePathStyle(true);
        }
        this.s3Client = builder.build();
    }

    private String bucketName(String scopeId) {
        return bucketPrefix + "-" + scopeId;
    }

    private String objectKey(String path) {
        return path;
    }

    @Override
    public void write(String scopeId, String path, byte[] content) {
        String bucket = bucketName(scopeId);
        String key = objectKey(path);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            log.error("Failed to write S3 object: bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("Failed to write S3 object: " + path, e);
        }
    }

    @Override
    public void write(String scopeId, String path, InputStream content, long size) {
        String bucket = bucketName(scopeId);
        String key = objectKey(path);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(content, size));
        } catch (S3Exception e) {
            log.error("Failed to write S3 object from stream: bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("Failed to write S3 object from stream: " + path, e);
        }
    }

    @Override
    public byte[] read(String scopeId, String path) {
        String bucket = bucketName(scopeId);
        String key = objectKey(path);
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            log.error("Failed to read S3 object: bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("Failed to read S3 object: " + path, e);
        }
    }

    @Override
    public InputStream readStream(String scopeId, String path) {
        String bucket = bucketName(scopeId);
        String key = objectKey(path);
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return s3Client.getObject(request);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            log.error("Failed to read S3 object stream: bucket={}, key={}", bucket, key, e);
            throw new RuntimeException("Failed to read S3 object stream: " + path, e);
        }
    }

    @Override
    public boolean exists(String scopeId, String path) {
        String bucket = bucketName(scopeId);
        String key = objectKey(path);
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3Client.headObject(request);
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new RuntimeException("Failed to check S3 object existence: " + path, e);
        }
    }

    @Override
    public void delete(String scopeId, String path) {
        String bucket = bucketName(scopeId);
        String key = objectKey(path);
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3Client.deleteObject(request);
        } catch (S3Exception e) {
            log.warn("Failed to delete S3 object: bucket={}, key={}", bucket, key, e);
        }
    }

    @Override
    public String getUrl(String scopeId, String path) {
        String bucket = bucketName(scopeId);
        String key = objectKey(path);
        if (publicUrl != null && !publicUrl.isEmpty()) {
            return publicUrl + "/" + bucket + "/" + key;
        }
        return bucket + "/" + key;
    }

    @Override
    public void ensureBucket(String scopeId) {
        String bucket = bucketName(scopeId);
        try {
            HeadBucketRequest request = HeadBucketRequest.builder()
                    .bucket(bucket)
                    .build();
            s3Client.headBucket(request);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                try {
                    CreateBucketRequest request = CreateBucketRequest.builder()
                            .bucket(bucket)
                            .build();
                    s3Client.createBucket(request);
                    log.info("Created S3 bucket: {}", bucket);
                } catch (S3Exception ce) {
                    log.error("Failed to create S3 bucket: {}", bucket, ce);
                    throw new RuntimeException("Failed to create S3 bucket: " + bucket, ce);
                }
            } else {
                throw new RuntimeException("Failed to check S3 bucket: " + bucket, e);
            }
        }
    }

    @Override
    public java.time.LocalDateTime getLastModifiedTime(String scopeId, String path) {
        String bucket = bucketName(scopeId);
        String key = objectKey(path);
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return java.time.LocalDateTime.ofInstant(
                s3Client.headObject(request).lastModified(),
                java.time.ZoneId.systemDefault());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            log.error("Failed to get last modified time for S3 object: bucket={}, key={}", bucket, key, e);
            return null;
        }
    }

    @Override
    public void append(String scopeId, String path, byte[] content) {
        byte[] existing = read(scopeId, path);
        if (existing != null) {
            byte[] combined = new byte[existing.length + content.length];
            System.arraycopy(existing, 0, combined, 0, existing.length);
            System.arraycopy(content, 0, combined, existing.length, content.length);
            write(scopeId, path, combined);
        } else {
            write(scopeId, path, content);
        }
    }
}