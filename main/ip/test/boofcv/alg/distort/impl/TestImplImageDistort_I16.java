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

package boofcv.alg.distort.impl;

import boofcv.alg.distort.ImageDistort;
import boofcv.alg.interpolate.InterpolatePixel;
import boofcv.alg.interpolate.InterpolatePixelS;
import boofcv.struct.distort.PixelTransform_F32;
import boofcv.struct.image.ImageSInt16;
import boofcv.struct.image.ImageType;


/**
 * @author Peter Abeles
 */
public class TestImplImageDistort_I16 extends GeneralImageDistortTests<ImageSInt16>{

	public TestImplImageDistort_I16() {
		super(ImageType.single(ImageSInt16.class));
	}

	@Override
	public ImageDistort<ImageSInt16,ImageSInt16> createDistort(PixelTransform_F32 dstToSrc,
															   InterpolatePixel<ImageSInt16> interp ) {
		ImageDistort<ImageSInt16,ImageSInt16> ret = new ImplImageDistort_I16<ImageSInt16,ImageSInt16>((InterpolatePixelS)interp);
		ret.setModel(dstToSrc);
		return ret;
	}
}
