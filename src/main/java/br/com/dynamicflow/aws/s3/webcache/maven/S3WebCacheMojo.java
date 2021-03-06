package br.com.dynamicflow.aws.s3.webcache.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPOutputStream;

import javax.activation.MimetypesFileTypeMap;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;

import br.com.dynamicflow.aws.s3.webcache.util.WebCacheConfig;
import br.com.dynamicflow.aws.s3.webcache.util.WebCacheManager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.cee.common.io.RelativePathUtils;
import com.cee.common.io.StringXORer;

/**
 * @prefix s3-webcache
 * @requiresProject true
 * @requiresOnline true
 * @goal upload
 * @phase prepare-package
 * @description Uploads static resources to a AWS S3 Bucket
 * 
 */
public class S3WebCacheMojo extends AbstractMojo {

	//private static final String DIGEST_NONE = "none";
	private static final String DIGEST_SHA512 = "sha512";
	private static final String DIGEST_SHA384 = "sha384";
	private static final String DIGEST_SHA256 = "sha256";
	private static final String DIGEST_SHA1 = "sha1";
	private static final String DIGEST_MD5 = "md5";
	
	private static final List<String> DIGEST_OPTIONS;
	static {
		DIGEST_OPTIONS = new ArrayList<String>();
		//DIGEST_OPTIONS.add(DIGEST_NONE);
		DIGEST_OPTIONS.add(DIGEST_MD5);
		DIGEST_OPTIONS.add(DIGEST_SHA1);
		DIGEST_OPTIONS.add(DIGEST_SHA256);
		DIGEST_OPTIONS.add(DIGEST_SHA384);
		DIGEST_OPTIONS.add(DIGEST_SHA512);
	}
	
	private static final String DIGEST_LEVEL_FILE = "file";
	private static final String DIGEST_LEVEL_GLOBAL = "global";
	
	private static final List<String> DIGEST_LEVELS;
	static {
		DIGEST_LEVELS = new ArrayList<String>();
		DIGEST_LEVELS.add(DIGEST_LEVEL_FILE);
		DIGEST_LEVELS.add(DIGEST_LEVEL_GLOBAL);
	}

	private static final String CONTENT_ENCODING_PLAIN = "plain";
	private static final String CONTENT_ENCODING_GZIP = "gzip";

	private static final List<String> CONTENT_ENCODING_OPTIONS;
	static {
		CONTENT_ENCODING_OPTIONS = new ArrayList<String>();
		CONTENT_ENCODING_OPTIONS.add(CONTENT_ENCODING_PLAIN);
		CONTENT_ENCODING_OPTIONS.add(CONTENT_ENCODING_GZIP);
	}
	
	private static final SimpleDateFormat httpDateFormat;
	static {
		httpDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
		httpDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
	}
	
	private static final Date EXPIRES_DATE;
	static {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.YEAR, 10);
		EXPIRES_DATE = calendar.getTime();
	}
	
	private static final int BUFFER_SIZE = 4096;
	
	public static final String S3_URL = "s3.amazonaws.com";
	
	/**
	 * Setup the Mime Type mappings. These are some of the most common types
	 * below but this list can use updating. If a type is not in the list it
	 * will generally be marked as application/octet-stream and will typically
	 * not be handled correctly by AWS S3 (depending on your use case).
	 */
	private static final MimetypesFileTypeMap mimeMap = new MimetypesFileTypeMap();
	static {
		mimeMap.addMimeTypes("application/javascript js");
		mimeMap.addMimeTypes("image/png png");
		mimeMap.addMimeTypes("image/gif gif");
		mimeMap.addMimeTypes("image/jpeg jpg jpeg");
		mimeMap.addMimeTypes("image/svg+xml svg");
		mimeMap.addMimeTypes("image/tiff tiff");
		mimeMap.addMimeTypes("image/vnd.microsoft.icon ico");
		mimeMap.addMimeTypes("application/json json");
		mimeMap.addMimeTypes("text/css css");
		mimeMap.addMimeTypes("text/csv csv");
		mimeMap.addMimeTypes("text/html html");
		mimeMap.addMimeTypes("text/plain txt");
		mimeMap.addMimeTypes("text/vcard vcard");
		mimeMap.addMimeTypes("text/xml xml");
		mimeMap.addMimeTypes("video/x-flv flv");
		mimeMap.addMimeTypes("application/zip zip");
		mimeMap.addMimeTypes("application/font-woff woff");
	}
	
	/**
	 * @parameter property="accessKey"
	 */
	private String accessKey;
	
	/**
	 * @parameter property="secretKey"
	 */
	private String secretKey;
	
	/**
	 * @parameter property="bucketName" 
	 */
	private String bucketName;
	
	/**
	 * @parameter property="hostName"
	 */
	private String hostName;
	
	/**
	* The comma separated list of tokens to that will not be processed. 
	* By default excludes all files under WEB-INF and META-INF directories.
	* Note that you can use the Java Regular Expressions engine to
	* include and exclude specific pattern using the expression %regex[].
	* Hint: read the about (?!Pattern).
	*
	* @parameter
	*/
	private List<String> excludes;
	
	/**
	* The comma separated list of tokens that will br processed. 
	* By default contains the extensions: gif, jpg, tif, png, pdf, swf, eps, js and css.
	* Note that you can use the Java Regular Expressions engine 
	* to include and exclude specific pattern
	* using the expression %regex[].
	*
	* @parameter
	*/
	private List<String> includes;
	
	/**
	* The directory where the webapp is built.
	*
	* @parameter default-value="${project.build.directory}/${project.build.finalName}"
	* @required
	*/
	private File outputDirectory;
	
	/**
	* Single directory for extra files to include in the WAR. This is where
	* you place your JSP files.
	*
	* @parameter default-value="${basedir}/src/main/webapp"
	* @required
	*/
	private File inputDirectory;
	
	/**
	* Directory to encode files before uploading
	*
	* @parameter default-value="${project.build.directory}/s3-webcache/temp"
	* @required
	*/
	private File tmpDirectory;
	
	/**
	* Manifest File
	*
	* @parameter default-value="${project.build.directory}/${project.build.finalName}/WEB-INF/s3-webcache.xml"
	* @required
	*/
	private File manifestFile;
	
	/**
	* Content Encoding Type
	*
	* @parameter default-value="gzip"
	* @required
	*/
	private String contentEncoding;
	
	/**
	* Digest Type
	*
	* @parameter default-value="none"
	* @required
	*/
	private String digestType;
	
	/**
	 * Digest Level
	 * 
	 * @parameter default-value="global"
	 * @required
	 */
	private String digestLevel;
	
	/** 
     * The Maven project. 
     * 
     * @parameter expression="${project}" 
     * @required 
     * @readonly 
     */ 
    private MavenProject project; 

	private String globalDigest = "";
	
	public void execute() throws MojoExecutionException {
		getLog().info("tmpDirectory " + tmpDirectory.getPath());
		getLog().info("inputDirectory " + inputDirectory.getPath());
		getLog().info("outputDirectory " + outputDirectory.getPath());
		getLog().info("manifestFile " + manifestFile.getPath());
		getLog().info("includes " + includes);
		getLog().info("excludes " + excludes);
		
		if (hostName==null || hostName.length()==0) {
			hostName=bucketName+"."+S3_URL;
		}
		getLog().info("using hostName " + hostName);
		
		if (!contains(DIGEST_OPTIONS, digestType)) {
			throw new MojoExecutionException("digestType "+digestType+" must be in "+DIGEST_OPTIONS);
		}
		getLog().info("using digestType " + digestType);
		getLog().info("using digestLevel " + digestLevel);
		
		WebCacheConfig webCacheConfig = new WebCacheConfig(hostName);
		
		BasicAWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);
		AmazonS3Client client = new AmazonS3Client(awsCredentials);
		
		try {
			getLog().info( "determining files that should be uploaded" );
			getLog().info("");
			List<String> fileNames = FileUtils.getFileNames(inputDirectory, convertToString(includes), convertToString(excludes), true, false);
			
			// Compute the Global Digest, if needed
			if (digestLevel.equalsIgnoreCase(DIGEST_LEVEL_GLOBAL)) {
				getLog().info("running global digest...");
				Collections.sort(fileNames, Collator.getInstance());
				
				/**
				 * What we do here is digest all the files and MD5 those digests together
				 */
				byte[] globalBytes = null;
				for (String fileName : fileNames) {
					byte[] fileDigest = rawDigest(new File(fileName));
					if (globalBytes == null) {
						globalBytes = fileDigest;
					} else {
						globalBytes = StringXORer.xorWithKey(globalBytes, fileDigest);
					}
					//getLog().info(" >> global so far: " + Hex.encodeHexString(DigestUtils.md5(globalBytes)));
				}
				globalDigest = Hex.encodeHexString(DigestUtils.md5(globalBytes));
				getLog().info("global digest is " + globalDigest);
				project.getProperties().put("project.web.digest", globalDigest);
			}
			
			// Upload the files
			for (String fileName: fileNames) {
				processFile(client, webCacheConfig, new File(fileName));
			}
		} catch (IOException e) {
			throw new MojoExecutionException("cannot determine the files to be processed", e);
		}
		
		generateConfigManifest(webCacheConfig);
	}

	private void processFile(AmazonS3Client client, WebCacheConfig webCacheConfig, File file)
			throws MojoExecutionException {
		getLog().info("start processing file "+file.getPath()); 	

		String relativePath = getPlainRelativePath(file);
		getLog().info("Relative Path to file: " + relativePath);

		String contentType = getMimeType(file);
		getLog().info("Mime Type: " + contentType);
		File encodedFile = encodeFile(file);
		
		String digest = calculateDigest(encodedFile);
		ObjectMetadata objectMetadata = retrieveObjectMetadata(client, digest);
		
		if (objectMetadata != null && objectMetadata.getETag().equals(calculateETag(encodedFile))) {
			getLog().info("the object "+file.getName()+" stored at "+bucketName+" does not require update");
		} else {
			uploadFile(client, encodedFile, digest, contentType);
		}
		webCacheConfig.addToCachedFiles(relativePath, digest);
		
		getLog().info("finnish processing file "+file.getPath());
		getLog().info("");
	}

	private File encodeFile(File file) throws MojoExecutionException {
		getLog().info("contentEncoding file "+file.getPath()+" using "+contentEncoding);
		if (!tmpDirectory.exists() && !tmpDirectory.mkdirs()) {
			throw new MojoExecutionException("cannot create directory "+tmpDirectory);
		}
		
		File encodedFile = null;
		if (CONTENT_ENCODING_PLAIN.equalsIgnoreCase(contentEncoding)) {
			encodedFile = file;
		}
		else if (CONTENT_ENCODING_GZIP.equals(contentEncoding)) {
			FileInputStream fis = null;
			GZIPOutputStream gzipos = null;
			try {
				byte buffer[] = new byte[BUFFER_SIZE];
				encodedFile = File.createTempFile(file.getName()+"-",".tmp", tmpDirectory);
				fis = new FileInputStream(file);
				gzipos = new GZIPOutputStream(new FileOutputStream(encodedFile));
				int read = 0;
				do {
					read = fis.read(buffer, 0, buffer.length);
					if (read>0)
						gzipos.write(buffer, 0, read);
				} while (read>=0);
			} catch (Exception e) {
				throw new MojoExecutionException("could not process "+file.getName(),e);
			} finally {
				if (fis!= null)
					try {
						fis.close();
					} catch (IOException e) {
						throw new MojoExecutionException("could not process "+file.getName(),e);
					}
				if (gzipos!= null)
					try {
						gzipos.close();
					} catch (IOException e) {
						throw new MojoExecutionException("could not process "+encodedFile.getName(),e);
					}
			}
		}
		
		return encodedFile;
	}

	private void uploadFile(AmazonS3Client client, File file, String remoteFileName, String contentType) throws MojoExecutionException {
		getLog().info("uploading file "+file+" to "+bucketName);	
		try {
			getLog().info("content type for "+file.getName()+" is "+contentType);
			
			// Object Meta Data
			ObjectMetadata objectMetadata = new ObjectMetadata();
			objectMetadata.setContentLength(file.length());
			//objectMetadata.setHeader("Content-Disposition", "filename=" + file.getName());
			objectMetadata.setHeader("Cache-Control", "public, s-maxage=315360000, max-age=315360000");
			//objectMetadata.setHeader("Expires", httpDateFormat.format(EXPIRES_DATE));
			objectMetadata.setHeader("Expires", EXPIRES_DATE.getTime());
			objectMetadata.setLastModified(new Date(file.lastModified()));
			if (!CONTENT_ENCODING_PLAIN.equalsIgnoreCase(contentEncoding)) {
				objectMetadata.setContentEncoding(contentEncoding.toLowerCase());
			}
			objectMetadata.setContentType(contentType);
			
			// Upload Object/File
			client.putObject(bucketName, remoteFileName, new FileInputStream(file), objectMetadata);
			
			// Access Control List
			client.setObjectAcl(bucketName, remoteFileName, CannedAccessControlList.PublicRead);
			
		} catch (AmazonServiceException e) {
			throw new MojoExecutionException("could not upload file "+file.getName(),e);
		} catch (AmazonClientException e) {
			throw new MojoExecutionException("could not upload file "+file.getName(),e);
		} catch (FileNotFoundException e) {
			getLog().error(e);
		}
	}
	
	private ObjectMetadata retrieveObjectMetadata(AmazonS3Client client, String remoteFileName) throws MojoExecutionException {
		getLog().info("retrieving metadata for "+remoteFileName);
		ObjectMetadata objectMetadata = null;

		try {
			objectMetadata = client.getObjectMetadata(bucketName, remoteFileName);
			getLog().info( "  ETag: " + objectMetadata.getETag());
			getLog().info( "  ContentMD5: " + objectMetadata.getContentMD5());
			getLog().info( "  ContentType: " + objectMetadata.getContentType());
			getLog().info( "  CacheControl: " + objectMetadata.getCacheControl());
			getLog().info( "  ContentEncoding: " + objectMetadata.getContentEncoding());
			getLog().info( "  ContentDisposition: " + objectMetadata.getContentDisposition());
			getLog().info( "  ContentLength: " + objectMetadata.getContentLength());
			getLog().info( "  LastModified: " + objectMetadata.getLastModified());
		} catch (AmazonServiceException e) {
			getLog().info("  no object metadata found");
		} catch (AmazonClientException e) {
			throw new MojoExecutionException("  could not retrieve object metadata",e);
		}
		return objectMetadata;
	}

	private String calculateETag(File file) throws MojoExecutionException {
		String eTag = null;
		try {
			eTag = Hex.encodeHexString(DigestUtils.md5(new FileInputStream(file)));
		} catch (Exception e) {
			throw new MojoExecutionException("could not calculate ETag for "+file.getName(),e);
		} 
		getLog().info("eTag for "+file.getName()+" is "+eTag);
		return eTag;
	}
	
	private boolean contains(List<String> digestOptions, String search) {
		if (search == null) {
			throw new IllegalArgumentException("search cannot be null");
		}
		for (String item: digestOptions) {
			if (search.equalsIgnoreCase(item)) {
				return true;
			}
		}
		return false;
	}
	
	private String calculateDigest(File file) throws MojoExecutionException {
		if (digestLevel.equalsIgnoreCase(DIGEST_LEVEL_GLOBAL) && globalDigest != null && !globalDigest.isEmpty()) {
			return globalDigest + "/" + getPlainRelativePath(file);
		} else {
			return Hex.encodeHexString(rawDigest(file));
		}
		
		/*
		String digest = null;
		try {
			if (digestLevel.equalsIgnoreCase(DIGEST_LEVEL_GLOBAL) && globalDigest != null && !globalDigest.isEmpty()) {
				digest = globalDigest + "/" + getPlainRelativePath(file);
			}
			else if (DIGEST_MD5.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.md5(new FileInputStream(file)));
			} 
			else if (DIGEST_SHA1.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.sha(new FileInputStream(file)));
			}
			else if (DIGEST_SHA256.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.sha256(new FileInputStream(file)));
			}
			else if (DIGEST_SHA384.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.sha384(new FileInputStream(file)));
			} 
			else if (DIGEST_SHA512.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.sha512(new FileInputStream(file)));
			} else { // DIGEST_NONE
				throw new MojoExecutionException("No digest type set!!");
			}
		} catch (Exception e) {
			throw new MojoExecutionException("could not calculate digest for "+file.getName(),e);
		} 
		getLog().info("digest for "+file.getName()+" is "+digest);
		return digest;*/
	}
	
	protected byte[] rawDigest(File file) throws MojoExecutionException {
		try {
			if (DIGEST_MD5.equalsIgnoreCase(digestType)) {
				return DigestUtils.md5(new FileInputStream(file));
			} 
			else if (DIGEST_SHA1.equalsIgnoreCase(digestType)) {
				return DigestUtils.sha(new FileInputStream(file));
			}
			else if (DIGEST_SHA256.equalsIgnoreCase(digestType)) {
				return DigestUtils.sha256(new FileInputStream(file));
			}
			else if (DIGEST_SHA384.equalsIgnoreCase(digestType)) {
				return DigestUtils.sha384(new FileInputStream(file));
			} 
			else if (DIGEST_SHA512.equalsIgnoreCase(digestType)) {
				return DigestUtils.sha512(new FileInputStream(file));
			} else {
				throw new MojoExecutionException("No digest type set!!");
			}
		} catch (Exception e) {
			throw new MojoExecutionException("could not calculate digest for " + file.getName(), e);
		}
	}
	
	private void generateConfigManifest(WebCacheConfig webCacheConfig) throws MojoExecutionException {
		WebCacheManager webCacheManager = new WebCacheManager();
		webCacheManager.persistConfig(webCacheConfig, manifestFile);
	}
	
	private String convertToString(List<String> list) {
		StringBuilder builder = new StringBuilder();
		int i = 0;
		for (Iterator<?> iterator = list.iterator(); iterator.hasNext(); i++) {
			if (i > 0)
				builder.append(", ");
			builder.append(iterator.next());
		}
		return builder.toString();
	}
	
	/**
	 * Returns the plain, relative, unix-style path to a file
	 * (relative to the inputDirectory)
	 * @param file
	 * @return relative unix-style path
	 */
	protected String getPlainRelativePath(File file) {
		String relativePath = RelativePathUtils.getRelativePath(inputDirectory, file);
		return FilenameUtils.separatorsToUnix(relativePath);
	}
	
	/**
	 * Uses mime-util to get the mime type of a file
	 * @see http://stackoverflow.com/questions/8488491/how-to-accurately-determine-mime-data-from-a-file
	 * @param file
	 * @return content type of file as a string
	 */
	protected String getMimeType(File file) {
		//Collection<?> mimeTypes = MimeUtil.getMimeTypes(file);
		//return MimeUtil.getFirstMimeType(mimeTypes.toString()).toString();
		return mimeMap.getContentType(file);
	}
}
