package com.tradeflow.product.application;

import com.tradeflow.common.BusinessException;
import com.tradeflow.product.infrastructure.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 上传先在事务外校验和规范化，商品锁、图片、审计和幂等结果本地原子提交，不留下孤儿文件。 */
@Service
public class ProductImageService {
    private final ProductMapper products;private final ProductImageMapper images;private final AuthorityClient authority;
    private final IdempotentWork work;private final TransactionTemplate tx;
    public ProductImageService(ProductMapper products,ProductImageMapper images,AuthorityClient authority,IdempotentWork work,PlatformTransactionManager manager) {
        this.products=products;this.images=images;this.authority=authority;this.work=work;this.tx=new TransactionTemplate(manager);
    }
    public record ImageView(String imageNo,String productId,String url,String contentType,long sizeBytes) { }
    /** 上传权限在事务外检查；幂等指纹包含归属、商品和规范化内容摘要，同键异图返回冲突。 */
    public ImageView upload(Jwt jwt,String id,String key,MultipartFile file,String request,String trace) {
        String merchant=authority.selectedMerchant(authority.merchant(jwt,true));authority.requireActive(merchant);
        byte[] png=normalize(file);String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)); }
        catch(Exception e) { throw new IllegalStateException(e); }
        return work.run("MERCHANT",jwt.getSubject(),"UPLOAD_PRODUCT_IMAGE",key,new TreeMap<>(Map.of("merchantId",merchant,"productId",id,"sha256",hash)),ImageView.class,no->{
            var product=products.lockProduct(id);if(product==null||!merchant.equals(product.merchantId()))throw missing();
            if(images.count(id)>=10)throw new BusinessException(409,"IMAGE_LIMIT","每个商品最多十张图片");
            String imageNo="img_"+UUID.randomUUID().toString().replace("-","");images.insert(imageNo,id,merchant,png);
            products.audit(jwt.getSubject(),"UPLOAD_PRODUCT_IMAGE",id,request,trace,"上传商品图片","{}");
            return new ImageView(imageNo,id,"/api/v1/product-images/"+imageNo,"image/png",png.length);
        });
    }
    /** 草稿图片元数据只向当前有权限的商家开放；公开列表必须商品已经上架。 */
    public List<ImageView> list(String id,Jwt jwt,boolean merchant) {
        String owner=merchant?authority.selectedMerchant(authority.merchant(jwt,false)):null;
        var product=products.product(id);
        if(product==null || (merchant?!owner.equals(product.merchantId()):!"ON_SALE".equals(product.status())))throw missing();
        return images.list(id,owner).stream().map(row->new ImageView(row.imageNo(),row.productId(),"/api/v1/product-images/"+row.imageNo(),row.contentType(),row.sizeBytes())).toList();
    }
    /** 图片读取与元数据列表使用相同可见性，缓存禁止存储以使下架/删除立即生效。 */
    public byte[] content(String imageNo) { var row=images.content(imageNo);if(row==null)throw missing();return (byte[])row.get("content"); }
    /** 商家草稿预览与每次请求的店铺选择关联，禁止借公开图片接口查看草稿。 */
    public byte[] merchantContent(Jwt jwt,String imageNo) {
        String owner=authority.selectedMerchant(authority.merchant(jwt,false));
        var row=images.merchantContent(imageNo,owner);if(row==null)throw missing();return (byte[])row.get("content");
    }
    /** 删除新经营资料需有效商家；同事务写审计，重复删除无业务效果。 */
    public void remove(Jwt jwt,String id,String imageNo,String request,String trace) {
        String owner=authority.selectedMerchant(authority.merchant(jwt,true));authority.requireActive(owner);
        tx.executeWithoutResult(status->{var product=products.lockProduct(id);if(product==null||!owner.equals(product.merchantId()))throw missing();
            if(images.remove(id,owner,imageNo)>0) products.audit(jwt.getSubject(),"REMOVE_PRODUCT_IMAGE",id,request,trace,"删除商品图片","{}");});
    }
    /** 不信任扩展名、MIME和文件名；解码前检查尺寸，解码后重编码，拒绝SVG/GIF/压缩炸弹及超限内容。 */
    static byte[] normalize(MultipartFile file) {
        if(file==null||file.isEmpty()||file.getSize()>5242880)throw invalid();
        try(var raw=file.getInputStream();var input=ImageIO.createImageInputStream(raw)) {
            if(input==null)throw invalid();var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid();
            var reader=readers.next();
            try {
                String format=reader.getFormatName();if(!Set.of("png","jpeg","jpg").contains(format.toLowerCase(Locale.ROOT)))throw invalid();
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>8000||height>8000||(long)width*height>16000000)throw invalid();
                var decoded=reader.read(0);if(decoded==null)throw invalid();
                // 限长输出流在编码期间即时拒绝，避免噪声图重编码产生过大缓冲。
                var output=new ByteArrayOutputStream();
                try(var limited=new FilterOutputStream(output) {
                    private long size;
                    @Override public void write(int value)throws IOException {if(++size>5242880)throw new IOException("图片过大");out.write(value);}
                    @Override public void write(byte[] value,int offset,int length)throws IOException {if((size+=length)>5242880)throw new IOException("图片过大");out.write(value,offset,length);}
                }) { if(!ImageIO.write(decoded,"png",limited))throw invalid(); }
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch(BusinessException e) {throw e;}catch(Exception e) {throw invalid();}
    }
    private static BusinessException invalid() {return new BusinessException(400,"INVALID_PRODUCT_IMAGE","只接受不超过5MiB的PNG/JPEG静态图片，最多1600万像素");}
    private static BusinessException missing() {return new BusinessException(404,"PRODUCT_IMAGE_NOT_FOUND","商品图片不存在或不可见");}
}
