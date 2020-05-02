package com.revature.rekognition.service;

import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.CompareFacesMatch;
import com.amazonaws.services.rekognition.model.CompareFacesRequest;
import com.amazonaws.services.rekognition.model.CompareFacesResult;
import com.amazonaws.services.rekognition.model.ComparedFace;
import com.amazonaws.services.rekognition.model.Image;
import com.amazonaws.services.rekognition.model.S3Object;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.revature.rekognition.model.CompareRequest;
import com.revature.rekognition.model.CompareResult;
import com.revature.rekognition.model.CompareSeveralRequest;
import com.revature.rekognition.model.CompareSeveralResult;
import com.revature.rekognition.model.MatchedFace;
import com.revature.rekognition.model.UnmatchedFace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CompareService {
  private static final AmazonRekognition rekognitionClient =
      AmazonRekognitionClientBuilder.defaultClient();
  private static final Logger LOG = LoggerFactory.getLogger(CompareService.class);
  private static final String BUCKET_NAME = "revature-compare-bucket";

  /**
   * Use compareFaces() to compare a list of images against the first image in that list.
   * 
   * @param compareSeveralRequest The object containing the list images.
   * @return The results of the face comparisons.
   */
  public CompareSeveralResult compareSeveralFaces(CompareSeveralRequest compareSeveralRequest) {

    CompareSeveralRequest requestParameters;

    try {
      ObjectMapper objectMapper = new ObjectMapper();
      requestParameters = objectMapper.readValue(
          objectMapper.writeValueAsString(compareSeveralRequest), CompareSeveralRequest.class);
    } catch (JsonProcessingException e) {
      requestParameters = compareSeveralRequest;
    }

    CompareSeveralResult compareSeveralResult = new CompareSeveralResult(requestParameters,
        new LinkedList<MatchedFace>(), new LinkedList<UnmatchedFace>());
    String sourceImage = compareSeveralRequest.getImages().get(0);
    compareSeveralRequest.getImages().remove(0);

    for (String image : compareSeveralRequest.getImages()) {
      CompareRequest compareRequest = new CompareRequest();
      CompareResult compareResult;

      compareRequest.setSimilarityThreshold(compareSeveralRequest.getSimilarityThreshold());
      compareRequest.setQualityFilter(compareSeveralRequest.getQualityFilter());
      compareRequest.setLocal(compareSeveralRequest.isLocal());
      compareRequest.setSourceImage(sourceImage);
      compareRequest.setTargetImage(image);

      compareResult = compareFaces(compareRequest);

      MatchedFace matchedFace = new MatchedFace();
      UnmatchedFace unmatchedFace = new UnmatchedFace();

      if (compareResult.getUnmatchedFace().getConfidence() == null) {
        matchedFace.setSourceImage(compareResult.getRequestParameters().getSourceImage());
        matchedFace.setTargetImage(compareResult.getRequestParameters().getTargetImage());
        matchedFace.setMatchedFace(compareResult.getMatchedFace());
        compareSeveralResult.getMatchedFaces().add(matchedFace);
      } else {
        unmatchedFace.setSourceImage(compareResult.getRequestParameters().getSourceImage());
        unmatchedFace.setTargetImage(compareResult.getRequestParameters().getTargetImage());
        unmatchedFace.setUnmatchedFace(compareResult.getUnmatchedFace());
        compareSeveralResult.getUnmatchedFaces().add(unmatchedFace);
      }
    }

    return compareSeveralResult;
  }

  /**
   * Make a call to Amazon Rekognition to compare the two faces given in the
   * compareRequest.
   * 
   * @param compareRequest The object containing the images.
   * @return The results of the face comparison.
   */
  public CompareResult compareFaces(CompareRequest compareRequest) {
    CompareFacesRequest compareFacesRequest = new CompareFacesRequest();

    if (compareRequest.isLocal()) {
      compareFacesRequest.setSourceImage(new Image().withBytes(imageToByteBuffer(compareRequest.getSourceImage())));
      compareFacesRequest.setTargetImage(new Image().withBytes(imageToByteBuffer(compareRequest.getTargetImage())));
    } else {
      compareFacesRequest.setSourceImage(
          new Image().withS3Object(new S3Object().withBucket(BUCKET_NAME).withName(compareRequest.getSourceImage())));
      compareFacesRequest.setTargetImage(
          new Image().withS3Object(new S3Object().withBucket(BUCKET_NAME).withName(compareRequest.getTargetImage())));
    }

    if (compareRequest.getQualityFilter() != null) {
      compareFacesRequest.setQualityFilter(compareRequest.getQualityFilter());
    }

    if (compareRequest.getSimilarityThreshold() != null) {
      compareFacesRequest.setSimilarityThreshold(compareRequest.getSimilarityThreshold());
    }

    CompareFacesResult compareFacesResult = rekognitionClient.compareFaces(compareFacesRequest);
    CompareFacesMatch matchedFace = new CompareFacesMatch();
    ComparedFace unmatchedFace = new ComparedFace();

    if (compareFacesResult.getFaceMatches().size() > 0) {
      matchedFace = compareFacesResult.getFaceMatches().get(0);
      LOG.info(new StringBuilder("Matched Face: ")
          .append(compareRequest.getTargetImage())
          .toString());
      LOG.info(new StringBuilder("Confidence:   ")
          .append(matchedFace.getFace()
          .getConfidence()).append("%")
          .toString());
      LOG.info(new StringBuilder("Similarity:   ")
          .append(matchedFace.getSimilarity().toString())
          .append("%")
          .append("\n")
          .toString());
    } else {
      unmatchedFace = compareFacesResult.getUnmatchedFaces().get(0);
      LOG.info(new StringBuilder("Unmatched Face: ")
          .append(compareRequest.getTargetImage())
          .toString());
      LOG.info(new StringBuilder("Confidence:     ")
          .append(unmatchedFace.getConfidence())
          .append("%")
          .append("\n")
          .toString());
    }

    return new CompareResult(compareRequest, matchedFace, unmatchedFace);
  }

  /**
   * Converts the given path to an image into a ByteBuffer of that image.
   * 
   * @param image The path of image to convert.
   * @return A ByteBuffer of the image.
   */
  private ByteBuffer imageToByteBuffer(String image) {
    try {
      if (image != null) {
        BufferedImage bufferedImage = ImageIO.read(new File(image));
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "jpg", byteStream);
        return ByteBuffer.wrap(byteStream.toByteArray());

      } else {
        String exception = "The given image path is null. Cannot convert to a ByteBuffer.";

        LOG.info(exception);
        throw new RuntimeException(exception);
      }

    } catch (IOException e) {
      String exception = new StringBuilder("Failed to transform the image at '").append(image)
          .append("' into a ByteBuffer.").toString();

      LOG.info(exception);
      throw new RuntimeException(exception);
    }
  }
}