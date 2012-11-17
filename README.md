S3 WebCache Maven Plugin
========================

Original Code From: https://github.com/aro1976/aws-parent

S3 WebCache Maven Plugin uploads the static files from the src/main/webapp directory of a Java
Web Application to S3, which can then be placed behind CloudFront to use as a CDN.

## Installation
Add the following lines to your pom.xml (with the correct values of course).

    <properties>
    	<aws.accessKey>IASDHASDHASIDHWERBW2</aws.accessKey>
    	<aws.secretKey>eptWdHgsN4XU0N4SFlpZvzikGkTFnGF/nl/mEAhm</aws.secretKey>
    	<aws.bucketName>data.fs.mycompany</aws.bucketName>
    </properties>

