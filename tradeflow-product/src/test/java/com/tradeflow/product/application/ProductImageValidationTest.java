package com.tradeflow.product.application;

import com.tradeflow.common.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

/** 上传以实际文件内容判断格式并重编码，不能相信扩展名或浏览器提供的MIME。 */
class ProductImageValidationTest {
    @Test void validRasterIsNormalizedWithoutTrustingMimeOrName() throws Exception {
        var source=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(12,8,BufferedImage.TYPE_INT_RGB),"jpeg",source);
        var normalized=ProductImageService.normalize(new MockMultipartFile("file","../../bad.svg","text/html",source.toByteArray()));
        assertThat(normalized).startsWith((byte)137,(byte)80,(byte)78,(byte)71);
        var decoded=ImageIO.read(new java.io.ByteArrayInputStream(normalized));assertThat(decoded.getWidth()).isEqualTo(12);assertThat(decoded.getHeight()).isEqualTo(8);
    }
    @Test void activeContentAndOversizedUploadAreRejected() {
        assertThatThrownBy(()->ProductImageService.normalize(new MockMultipartFile("file","safe.png","image/png","<svg onload='alert(1)'/>".getBytes())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ProductImageService.normalize(new MockMultipartFile("file","large.png","image/png",new byte[5242881])))
                .isInstanceOf(BusinessException.class);
    }
    @Test void dimensionsAreRejectedBeforeFullDecode() throws Exception {
        var source=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8001,1,BufferedImage.TYPE_INT_RGB),"png",source);
        assertThatThrownBy(()->ProductImageService.normalize(new MockMultipartFile("file",source.toByteArray()))).isInstanceOf(BusinessException.class);
    }
    @Test void malformedAndEmptyUploadsAreRejected() {
        assertThatThrownBy(()->ProductImageService.normalize(new MockMultipartFile("file",new byte[0]))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ProductImageService.normalize(new MockMultipartFile("file",new byte[]{(byte)137,80,78,71}))).isInstanceOf(BusinessException.class);
    }
}
