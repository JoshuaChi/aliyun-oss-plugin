package com.fit2cloud.jenkins.aliyunoss;

import com.aliyun.oss.OSSClient;
import com.aliyun.oss.model.ObjectMetadata;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

public class AliyunOSSClient {
	private static final String fpSeparator = ";";

	public static boolean validateAliyunAccount(
			final String aliyunAccessKey, final String aliyunSecretKey) throws AliyunOSSException {
		try {
			OSSClient client = new OSSClient(aliyunAccessKey, aliyunSecretKey);
			client.listBuckets();
		} catch (Exception e) {
			throw new AliyunOSSException("阿里云账号验证失败：" + e.getMessage());
		}
		return true;
	}


	public static boolean validateOSSBucket(String aliyunAccessKey,
			String aliyunSecretKey, String bucketName) throws AliyunOSSException{
		try {
			OSSClient client = new OSSClient(aliyunAccessKey, aliyunSecretKey);
			client.getBucketLocation(bucketName);
		} catch (Exception e) {
			throw new AliyunOSSException("验证Bucket名称失败：" + e.getMessage());
		}
		return true;
	}
	
	public static int upload(AbstractBuild<?, ?> build,
							 BuildListener listener,
							 final String aliyunAccessKey,
							 final String aliyunSecretKey,
							 final String aliyunEndPointSuffix,
							 String bucketName,
							 String expFP,
							 String expVP) throws AliyunOSSException {
		OSSClient client = new OSSClient(aliyunAccessKey, aliyunSecretKey);
		String location = client.getBucketLocation(bucketName);
		String endpoint = "http://" + location + aliyunEndPointSuffix;
		client = new OSSClient(endpoint, aliyunAccessKey, aliyunSecretKey);
		int filesUploaded = 0; // Counter to track no. of files that are uploaded
		try {
			FilePath workspacePath = build.getWorkspace();
			if (workspacePath == null) {
				listener.getLogger().println("工作空间中没有任何文件.");
				return filesUploaded;
			}
			StringTokenizer strTokens = new StringTokenizer(expFP, fpSeparator);
			List<FilePath> paths = null;

			listener.getLogger().println("开始上传到阿里云OSS...");
			listener.getLogger().println("上传endpoint是：" + endpoint);

			while (strTokens.hasMoreElements()) {
				String fileName = strTokens.nextToken();
				String embeddedVP = null;
				if (fileName != null) {
					int embVPSepIndex = fileName.indexOf("::");
					if (embVPSepIndex != -1) {
						if (fileName.length() > embVPSepIndex + 1) {
							embeddedVP = fileName.substring(embVPSepIndex + 2, fileName.length());
							if (Utils.isNullOrEmpty(embeddedVP)) {
								embeddedVP = null;
							}
							if (embeddedVP != null	&& !embeddedVP.endsWith(Utils.FWD_SLASH)) {
								embeddedVP = embeddedVP + Utils.FWD_SLASH;
							}
						}
						fileName = fileName.substring(0, embVPSepIndex);
					}
				}

				if (Utils.isNullOrEmpty(fileName)) {
					return filesUploaded;
				}

				FilePath fp = new FilePath(workspacePath, fileName);

				if (fp.exists() && !fp.isDirectory()) {
					paths = new ArrayList<FilePath>();
					paths.add(fp);
				} else {
                    listener.getLogger().println("fileName: " + fileName);
					paths = fp.list();
				}

                filesUploaded = deepLoop(listener, bucketName, expVP, client, filesUploaded, paths, embeddedVP);
            }
		} catch (Exception e) {
			e.printStackTrace();
			throw new AliyunOSSException(e.getMessage(), e.getCause());
		}
		return filesUploaded;
	}

    private static int deepLoop(
            BuildListener listener, String bucketName,
            String expVP, OSSClient client, int filesUploaded,
            List<FilePath> paths, String embeddedVP) throws IOException, InterruptedException {

        listener.getLogger().println("Paths length：" + paths.size() + ". embeddedVP:" + embeddedVP +". expVP: " + expVP);
        if (paths.size() > 0) {
            listener.getLogger().println("Paths is not empty.");
            for (FilePath src : paths) {
                listener.getLogger().println("file path src name: " + src.getName());
                if (src.isDirectory()) {
                    listener.getLogger().println("Path is directory：" + src);
                    filesUploaded = deepLoop(listener, bucketName, expVP+src.getName()+"/", client, filesUploaded, src.list(), embeddedVP);
                }
                else {
                    listener.getLogger().println("Path is not directory：" + src);
                    getFileUploaded(listener, bucketName, expVP, client, embeddedVP, src);
                }
                filesUploaded++;
            }
        }
        return filesUploaded;
    }

    private static void getFileUploaded(
            BuildListener listener,
            String bucketName,
            String expVP,
            OSSClient client,
            String embeddedVP,
            FilePath src) throws IOException, InterruptedException {

        String key = "";
        if (Utils.isNullOrEmpty(expVP)
                && Utils.isNullOrEmpty(embeddedVP)) {
            key = src.getName();
        } else {
            String prefix = expVP;
            if (!Utils.isNullOrEmpty(embeddedVP)) {
                if (Utils.isNullOrEmpty(expVP)) {
                    prefix = embeddedVP;
                } else {
                    prefix = expVP + embeddedVP;
                }
            }
            key = prefix + src.getName();
        }

        listener.getLogger().println("key：" + key);
        long startTime = System.currentTimeMillis();

        InputStream inputStream = src.read();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) > -1 ) {
                baos.write(buffer, 0, len);
            }
            baos.flush();
            ObjectMetadata meta = new ObjectMetadata();

            MediaType mediaType = getMediaType(listener, src, baos);

            meta.setContentType(mediaType.toString());

            meta.setContentLength(src.length());
            client.putObject(bucketName, key, new ByteArrayInputStream(baos.toByteArray()), meta);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
            }
        }
        long endTime = System.currentTimeMillis();
        listener.getLogger().println("Uploaded object ["+ key + "] in " + getTime(endTime - startTime));
    }

    private static MediaType getMediaType(BuildListener listener, FilePath src, ByteArrayOutputStream baos) throws IOException {
        AutoDetectParser parser = new AutoDetectParser();
        Detector detector = parser.getDetector();
        Metadata md = new Metadata();
        md.add(Metadata.RESOURCE_NAME_KEY, src.getName());
        MediaType mediaType = detector.detect(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())), md);
        listener.getLogger().println(" - filename ["+ src.getName() + "] with content type [" + mediaType.toString() + "].");
        return mediaType;
    }

    public static String getTime(long timeInMills) {
		return DurationFormatUtils.formatDuration(timeInMills, "HH:mm:ss.S") + " (HH:mm:ss.S)";
	}

}
