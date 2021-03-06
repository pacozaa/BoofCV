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

package boofcv.io.jcodec;

import boofcv.struct.image.*;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;

/**
 * @author Peter Abeles
 */
public class UtilJCodec {
	/**
	 * Converts an image in JCodec format into one in BoofCV format.
	 * @param input JCodec image
	 * @param output BoofCV image
	 */
	public static void convertToBoof(Picture input, ImageBase output) {
		if( input.getColor() == ColorSpace.RGB ) {
			ImplConvertJCodecPicture.RGB_to_MSU8(input, (MultiSpectral) output);
		} else if( input.getColor() == ColorSpace.YUV420 ) {
			if( output instanceof MultiSpectral ) {
				MultiSpectral ms = (MultiSpectral)output;
				if( ms.getImageType().getDataType() == ImageDataType.U8 ) {
					ImplConvertJCodecPicture.yuv420_to_MsRgb_U8(input, ms);
				} else if( ms.getImageType().getDataType() == ImageDataType.F32 ) {
					ImplConvertJCodecPicture.yuv420_to_MsRgb_F32(input, ms);
				}
			} else if( output instanceof ImageUInt8 ) {
				ImplConvertJCodecPicture.yuv420_to_U8(input, (ImageUInt8) output);
			} else if( output instanceof ImageFloat32) {
				ImplConvertJCodecPicture.yuv420_to_F32(input, (ImageFloat32) output);
			} else {
				throw new RuntimeException("Unexpected output image type");
			}
		}
	}
}
