/*
 * Copyright (c) 2011-2015, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.fiducial;

import boofcv.abst.filter.binary.InputToBinary;
import boofcv.abst.geo.RefineEpipolar;
import boofcv.alg.distort.*;
import boofcv.alg.geo.h.HomographyLinear4;
import boofcv.alg.interpolate.InterpolatePixelS;
import boofcv.alg.shapes.polygon.BinaryPolygonConvexDetector;
import boofcv.core.image.border.BorderType;
import boofcv.core.image.border.FactoryImageBorder;
import boofcv.factory.distort.FactoryDistort;
import boofcv.factory.geo.EpipolarError;
import boofcv.factory.geo.FactoryMultiView;
import boofcv.factory.interpolate.FactoryInterpolation;
import boofcv.struct.calib.IntrinsicParameters;
import boofcv.struct.distort.*;
import boofcv.struct.geo.AssociatedPair;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageSingleBand;
import boofcv.struct.image.ImageUInt8;
import georegression.geometry.GeometryMath_F64;
import georegression.geometry.UtilPolygons2D_F64;
import georegression.struct.homography.UtilHomography;
import georegression.struct.point.Point2D_F64;
import georegression.struct.se.Se3_F64;
import georegression.struct.shapes.Polygon2D_F64;
import georegression.struct.shapes.Quadrilateral_F64;
import org.ddogleg.struct.FastQueue;
import org.ejml.data.DenseMatrix64F;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * Base class for square fiducial detectors.  Searches for quadrilaterals inside the image with a black border
 * and inner contours.  It then removes perspective and lens distortion from the candidate quadrilateral and
 * rendered onto a new image.  The just mentioned image is then passed on to the class which extends this one.
 * After being processed by the extending class, the corners are rotated to match and the 3D pose of the
 * target found.  Lens distortion is removed sparsely for performance reasons.
 * </p>
 *
 * <p>
 * Must call {@link #configure} before it can process an image.
 * </p>
 *
 * <p>
 * Target orientation. Corner 0 = (-r,r), 1 = (r,r) , 2 = (rr,-r) , 3 = (-r,-r).
 * </p>
 *
 * @author Peter Abeles
 */
public abstract class BaseDetectFiducialSquare<T extends ImageSingleBand> {

	// Storage for the found fiducials
	private FastQueue<FoundFiducial> found = new FastQueue<FoundFiducial>(FoundFiducial.class,true);

	// converts input image into a binary image
	InputToBinary<T> inputToBinary;
	// Detects the squares
	private BinaryPolygonConvexDetector<T> squareDetector;

	// image with lens and perspective distortion removed from it
	private ImageFloat32 square;

	// storage for binary image
	ImageUInt8 binary = new ImageUInt8(1,1);

	// Used to compute/remove distortion from perspective
	private HomographyLinear4 computeHomography = new HomographyLinear4(true);
	private RefineEpipolar refineHomography = FactoryMultiView.refineHomography(1e-4,100, EpipolarError.SAMPSON);
	private DenseMatrix64F H = new DenseMatrix64F(3,3);
	private DenseMatrix64F H_refined = new DenseMatrix64F(3,3);
	private List<AssociatedPair> pairsRemovePerspective = new ArrayList<AssociatedPair>();
	private ImageDistort<T,ImageFloat32> removePerspective;
	private PointTransformHomography_F32 transformHomography = new PointTransformHomography_F32();

	// used to compute 3D pose of target
	QuadPoseEstimator poseEstimator = new QuadPoseEstimator(1e-6,200);

	// transform from undistorted image to distorted
	PointTransform_F64 pointUndistToDist;

	// Storage for results of fiducial reading
	private Result result = new Result();

	// type of input image
	private Class<T> inputType;

	private boolean verbose = false;

	/**
	 * Configures the detector.
	 *
	 * @param inputToBinary Converts input image into a binary image
	 * @param squareDetector Detects the quadrilaterals in the image
	 * @param squarePixels  Number of pixels wide the image that stores the target's detector interior is.
	 * @param inputType Type of input image it's processing
	 */
	protected BaseDetectFiducialSquare(InputToBinary<T> inputToBinary,
									   BinaryPolygonConvexDetector<T> squareDetector,
									   int squarePixels,
									   Class<T> inputType) {

		if( squareDetector.getNumberOfSides()[0] != 4 )
			throw new IllegalArgumentException("quadDetector not configured to detect quadrilaterals");
		if( squareDetector.isOutputClockwise() )
			throw new IllegalArgumentException("output polygons needs to be counter-clockwise");

		this.inputToBinary = inputToBinary;
		this.squareDetector = squareDetector;
		this.inputType = inputType;
		this.square = new ImageFloat32(squarePixels,squarePixels);

		for (int i = 0; i < 4; i++) {
			pairsRemovePerspective.add(new AssociatedPair());
		}

		// add corner points in target frame.  Used to compute homography.  Target's center is at its origin
		// see comment in class JavaDoc above.  Note that the target's length is one below.  The scale factor
		// will be provided later one
		poseEstimator.setFiducial(-0.5,0.5,  0.5,0.5,  0.5,-0.5,  -0.5,-0.5);

		// this combines two separate sources of distortion together so that it can be removed in the final image which
		// is sent to fiducial decoder
		InterpolatePixelS<T> interp = FactoryInterpolation.nearestNeighborPixelS(inputType);
		interp.setBorder(FactoryImageBorder.single(inputType, BorderType.EXTENDED));
		removePerspective = FactoryDistort.distortSB(false, interp, ImageFloat32.class);
	}

	/**
	 * Specifies the image's intrinsic parameters and target size
	 *
	 * @param intrinsic Intrinsic parameters for the distortion free input image
	 * @param cache If there's lens distortion should it cache the transforms?  Speeds it up by about 12%.  Ignored
	 *              if no lens distortion
	 */
	public void configure( IntrinsicParameters intrinsic , boolean cache ) {

		PointTransform_F32 pointSquareToInput;
		if( intrinsic.isDistorted() ) {

			IntrinsicParameters intrinsicUndist = new IntrinsicParameters();

			// full view so that none of the pixels are discarded and to ensure that all pixels in the undistorted
			// image are bounded by the the input image's shape

			PointTransform_F32 pointDistToUndist = LensDistortionOps.
					transform_F32(AdjustmentType.FULL_VIEW, intrinsic, intrinsicUndist, false);
			PointTransform_F32 pointUndistToDist = LensDistortionOps.
					transform_F32(AdjustmentType.FULL_VIEW, intrinsic, null, true);
			PixelTransform_F32 distToUndist = new PointToPixelTransform_F32(pointDistToUndist);
			PixelTransform_F32 undistToDist = new PointToPixelTransform_F32(pointUndistToDist);

			if( cache ) {
				distToUndist = new PixelTransformCached_F32(intrinsic.width, intrinsic.height, distToUndist);
				undistToDist = new PixelTransformCached_F32(intrinsic.width, intrinsic.height, undistToDist);
			}

			squareDetector.setLensDistortion(intrinsic.width,intrinsic.height,distToUndist,undistToDist);

			pointSquareToInput = new SequencePointTransform_F32(transformHomography,pointUndistToDist);

			this.pointUndistToDist = LensDistortionOps.transform_F64(AdjustmentType.FULL_VIEW, intrinsic, null, true);

			intrinsic = intrinsicUndist;
		} else {
			pointSquareToInput = transformHomography;
			pointUndistToDist = new DoNothingTransform_F64();
		}

		poseEstimator.setIntrinsic(intrinsic);

		// provide intrinsic camera parameters
		PixelTransform_F32 squareToInput= new PointToPixelTransform_F32(pointSquareToInput);
		removePerspective.setModel(squareToInput);

		binary.reshape(intrinsic.width,intrinsic.height);
	}

	/**
	 * Examines the input image to detect fiducials inside of it
	 *
	 * @param gray Undistorted input image
	 */
	public void process( T gray ) {

		inputToBinary.process(gray,binary);
		squareDetector.process(gray,binary);
		FastQueue<Polygon2D_F64> candidates = squareDetector.getFound();

		found.reset();

		if( verbose ) System.out.println("---------- Got Polygons! "+candidates.size);
		// undistort the squares
		Quadrilateral_F64 q = new Quadrilateral_F64(); // todo predeclare
		for (int i = 0; i < candidates.size; i++) {
			// compute the homography from the input image to an undistorted square image
			Polygon2D_F64 p = candidates.get(i);
			UtilPolygons2D_F64.convert(p,q);

			// remember, visual clockwise isn't the same as math clockwise, hence
			// counter clockwise visual to the clockwise quad
			pairsRemovePerspective.get(0).set( 0            ,      0        , q.a.x , q.a.y);
			pairsRemovePerspective.get(1).set( square.width ,      0        , q.b.x , q.b.y );
			pairsRemovePerspective.get(2).set( square.width , square.height , q.c.x , q.c.y );
			pairsRemovePerspective.get(3).set( 0            , square.height , q.d.x , q.d.y );

			if( !computeHomography.process(pairsRemovePerspective,H) ) {
				if( verbose ) System.out.println("rejected initial homography");
				continue;
			}

			// refine homography estimate
			if( !refineHomography.fitModel(pairsRemovePerspective,H,H_refined) ) {
				if( verbose ) System.out.println("rejected refine homography");
				continue;
			}

			// pass the found homography onto the image transform
			UtilHomography.convert(H_refined, transformHomography.getModel());

			// TODO how perspective is removed is introducing artifacts.  If the "square" is larger
			// than the detected region and bilinear interpolation is used then pixels outside will// influence the value of pixels inside and shift things over.  this is all bad

			// remove the perspective distortion and process it
			removePerspective.apply(gray, square);

//			square.printInt();
			if( processSquare(square,result)) {
				prepareForOutput(q,result);

				if( verbose ) System.out.println("accepted!");
			} else {
				if( verbose ) System.out.println("rejected process square");
			}
		}
	}

	/**
	 * Takes the found quadrilateral and the computed 3D information and prepares it for output
	 */
	private void prepareForOutput(Quadrilateral_F64 imageShape, Result result) {
		// the rotation estimate, apply in counter clockwise direction
		// since result.rotation is a clockwise rotation in the visual sense, which
		// is CCW on the grid
		int rotationCCW = (4-result.rotation)%4;
		for (int j = 0; j < rotationCCW; j++) {

			rotateCounterClockwise(imageShape);
		}

		// save the results for output
		FoundFiducial f = found.grow();
		f.index = result.which;
		f.location.set(imageShape);

		// put it back into input image coordinates
		for (int j = 0; j < 4; j++) {
			Point2D_F64 a = f.location.get(j);
			pointUndistToDist.compute(a.x,a.y,a);
		}

		// estimate position
		computeTargetToWorld(imageShape, result.lengthSide, f.targetToSensor);
	}

	/**
	 * Rotates the corners on the quad
	 */
	private void rotateCounterClockwise(Quadrilateral_F64 quad) {
		Point2D_F64 a = quad.a;
		Point2D_F64 b = quad.b;
		Point2D_F64 c = quad.c;
		Point2D_F64 d = quad.d;

		quad.a = b;
		quad.b = c;
		quad.c = d;
		quad.d = a;
	}

	/**
	 * Given observed location of corners, compute the transform from target to world frame.
	 * See code comments for correct ordering of corners in quad.
	 *
	 * @param quad (Input) Observed location of corner points in pixels the specified order.
	 * @param lengthSide (Input) Length of a side on the square
	 * @param targetToWorld (output) transform from target to world frame.
	 */
	public void computeTargetToWorld( Quadrilateral_F64 quad , double lengthSide , Se3_F64 targetToWorld )
	{
		if( !poseEstimator.process(quad) ) {
			throw new RuntimeException("Failed on pose estimation!");
		}

		targetToWorld.set( poseEstimator.getWorldToCamera() );
		GeometryMath_F64.scale(targetToWorld.getT(),lengthSide);
	}

	/**
	 * Returns list of found fiducials
	 */
	public FastQueue<FoundFiducial> getFound() {
		return found;
	}

	/**
	 * Processes the detected square and matches it to a known fiducial.  Black border
	 * is included.
	 *
	 * @param square Image of the undistorted square
	 * @param result Which target and its orientation was found
	 * @return true if the square matches a known target.
	 */
	protected abstract boolean processSquare( ImageFloat32 square , Result result );

	public BinaryPolygonConvexDetector getSquareDetector() {
		return squareDetector;
	}

	public ImageUInt8 getBinary() {
		return binary;
	}

	public Class<T> getInputType() {
		return inputType;
	}

	public static class Result {
		int which;
		// length of one of the sides in world units
		double lengthSide;
		// amount of clockwise rotation.  Each value = +90 degrees
		// Just to make things confusion, the rotation is done in the visual clockwise, which
		// is a counter-clockwise rotation when you look at the actual coordinates
		int rotation;
	}
}
