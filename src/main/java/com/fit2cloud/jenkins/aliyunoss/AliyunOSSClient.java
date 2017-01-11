package com.fit2cloud.jenkins.aliyunoss;

import com.aliyun.oss.OSSClient;
import com.aliyun.oss.model.ObjectMetadata;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
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
	
	public static int upload(AbstractBuild<?, ?> build, BuildListener listener,
			final String aliyunAccessKey, final String aliyunSecretKey, final String aliyunEndPointSuffix, String bucketName,String expFP,String expVP) throws AliyunOSSException {
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
			FilePath[] paths = null;

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
					paths = new FilePath[1];
					paths[0] = fp;
				} else {
					paths = workspacePath.list(fileName);
				}

				if (paths.length != 0) {
					for (FilePath src : paths) {
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
						long startTime = System.currentTimeMillis();
						InputStream inputStream = src.read();
						try {
							ObjectMetadata meta = new ObjectMetadata();

							AutoDetectParser parser = new AutoDetectParser();
							Detector detector = parser.getDetector();
							Metadata md = new Metadata();
							md.add(Metadata.RESOURCE_NAME_KEY, src.getName());
                            MediaType mediaType = detector.detect(new BufferedInputStream(inputStream), md);
                            listener.getLogger().println(" - filename ["+ src.getName() + "] with content type [" + mediaType.toString() + "].");

                            meta.setContentType(mediaType.toString());

							meta.setContentLength(src.length());
							client.putObject(bucketName, key, inputStream, meta);
						} finally {
							try {
								inputStream.close();
							} catch (IOException e) {
							}
						}
						long endTime = System.currentTimeMillis();
						listener.getLogger().println("Uploaded object ["+ key + "] in " + getTime(endTime - startTime));
						filesUploaded++;
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new AliyunOSSException(e.getMessage(), e.getCause());
		}
		return filesUploaded;
	}
	
	public static String getTime(long timeInMills) {
		return DurationFormatUtils.formatDuration(timeInMills, "HH:mm:ss.S") + " (HH:mm:ss.S)";
	}

}
