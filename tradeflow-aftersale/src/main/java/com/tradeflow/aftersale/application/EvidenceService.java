package com.tradeflow.aftersale.application;

import com.tradeflow.aftersale.infrastructure.*;
import com.tradeflow.common.BusinessException;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 受控本地图片存储；数据库仲裁绑定与清理，文件IO不放在业务事务中。 */
@Service
public class EvidenceService {
 private final AftersaleStore db;private final Authority authority;private final TransactionTemplate tx;private final Path root;
 public EvidenceService(AftersaleStore db,Authority authority,TransactionTemplate tx,Environment env)throws IOException{this.db=db;this.authority=authority;this.tx=tx;Path configured=Path.of(env.getProperty("tradeflow.evidence-directory",".local/evidence")).toAbsolutePath().normalize();Files.createDirectories(configured);root=configured.toRealPath();}
 public record FileView(String objectKey,String mediaType,long sizeBytes){}
 public record Download(byte[] bytes,String mediaType){}
 /** 有效买家或商家上传；限制实际流大小，按文件头识别类型，永不使用客户端文件名。 */
 public FileView upload(Jwt jwt,MultipartFile file){var user=authority.check(jwt,"evidence");if(file==null||file.isEmpty()||file.getSize()>5242880)throw error(400,"INVALID_EVIDENCE","图片为空或超过5MB");byte[] bytes;try(var in=file.getInputStream()){bytes=in.readNBytes(5242881);}catch(IOException e){throw unavailable();}if(bytes.length==0||bytes.length>5242880)throw error(400,"INVALID_EVIDENCE","图片为空或超过5MB");String media=media(bytes);String key="ev_"+UUID.randomUUID().toString().replace("-","");Path path=path(key);try{Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);tx.executeWithoutResult(s->db.jdbc().update("INSERT INTO evidence_file(object_key,owner_id,media_type,size_bytes,content_hash) VALUES(?,?,?,?,?)",key,user.userId(),media,bytes.length,hash(bytes)));}catch(IOException e){try{Files.deleteIfExists(path);}catch(IOException ignored){}throw unavailable();}catch(RuntimeException e){/* 数据库提交结果可能未知，保留已写文件供核对，不能误删可能已登记的证据。 */throw unavailable();}return new FileView(key,media,bytes.length);}
 /** 调用者已持售后主单锁并在事务中；证据有序锁定，与清理墓碑原子竞争，一文件只能绑定一次。 */
 public void bind(String no,String owner,String purpose,List<String> keys){if(keys==null||keys.isEmpty())return;if(keys.size()>6||new HashSet<>(keys).size()!=keys.size())throw error(400,"INVALID_EVIDENCE","证据最多6张且不能重复");Integer bound=db.jdbc().queryForObject("SELECT COUNT(*) FROM aftersale_evidence WHERE aftersale_no=?",Integer.class,no);if(bound==null||bound+keys.size()>6)throw error(409,"EVIDENCE_LIMIT_EXCEEDED","每张售后申请和验收合计最多6张证据");for(String key:keys.stream().sorted().toList()){path(key);var rows=db.jdbc().queryForList("SELECT owner_id,state FROM evidence_file WHERE object_key=? FOR UPDATE",key);if(rows.isEmpty()||!owner.equals(rows.get(0).get("owner_id"))||!"UPLOADED".equals(rows.get(0).get("state")))throw error(409,"EVIDENCE_NOT_AVAILABLE","图片不属于本人、已绑定或已清理");if(db.jdbc().update("UPDATE evidence_file SET state='BOUND' WHERE object_key=? AND state='UPLOADED'",key)!=1)throw error(409,"EVIDENCE_NOT_AVAILABLE","图片已不可绑定");db.jdbc().update("INSERT INTO aftersale_evidence(aftersale_no,object_key,purpose) VALUES(?,?,?)",no,key,purpose);}}
 /** 下载使用最新身份与售后归属，上传者可查看未绑定图片，平台可查看；不接受任意URL或路径。 */
 public Download download(Jwt jwt,String key){path(key);var user=authority.check(jwt,"evidence");var rows=db.jdbc().queryForList("SELECT owner_id,media_type,size_bytes,content_hash,state FROM evidence_file WHERE object_key=?",key);if(rows.isEmpty()||!Set.of("UPLOADED","BOUND").contains(rows.get(0).get("state")))throw error(404,"EVIDENCE_NOT_FOUND","图片不存在");var r=rows.get(0);boolean allowed=user.userId().equals(r.get("owner_id"))||user.roles().contains("PLATFORM_ADMIN");if(!allowed){var links=db.jdbc().queryForList("SELECT a.user_id,a.merchant_id FROM aftersale_evidence e JOIN aftersale a ON a.aftersale_no=e.aftersale_no WHERE e.object_key=?",key);if(!links.isEmpty()){var a=links.get(0);allowed=user.userId().equals(a.get("user_id"))||(user.merchantIds().contains(a.get("merchant_id"))&&java.util.Set.of("ADMIN","OPERATOR").contains(user.merchantRoles().getOrDefault(a.get("merchant_id"),"")));}}if(!allowed)throw error(404,"EVIDENCE_NOT_FOUND","图片不存在");try{Path p=path(key);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)!=((Number)r.get("size_bytes")).longValue())throw unavailable();byte[] bytes=Files.readAllBytes(p);if(!hash(bytes).equals(r.get("content_hash")))throw unavailable();return new Download(bytes,(String)r.get("media_type"));}catch(IOException e){throw unavailable();}}
 /** 每轮有限清理一天前未绑定文件；先提交墓碑再删文件，失败保留墓碑下轮重试，已绑定永不清理。 */
 @Scheduled(fixedDelay=60000,initialDelay=60000)
 public void cleanup(){var keys=db.jdbc().queryForList("SELECT object_key FROM evidence_file WHERE state='TOMBSTONED' OR (state='UPLOADED' AND created_at<CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY) ORDER BY created_at LIMIT 20",String.class);for(String key:keys){Boolean claimed=tx.execute(s->{var rows=db.jdbc().queryForList("SELECT state FROM evidence_file WHERE object_key=? FOR UPDATE",key);if(rows.isEmpty())return false;String state=(String)rows.get(0).get("state");if("TOMBSTONED".equals(state))return true;return db.jdbc().update("UPDATE evidence_file SET state='TOMBSTONED' WHERE object_key=? AND state='UPLOADED' AND created_at<CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY",key)==1;});if(!Boolean.TRUE.equals(claimed))continue;try{Files.deleteIfExists(path(key));db.jdbc().update("UPDATE evidence_file SET state='DELETED' WHERE object_key=? AND state='TOMBSTONED'",key);}catch(IOException ignored){/* 保留墓碑，下一轮按原键重试删除。 */}}}
 /** 服务端随机键只映射固定目录的直接子文件，拒绝符号链接。 */
 private Path path(String key){if(key==null||!key.matches("ev_[a-f0-9]{32}"))throw error(400,"INVALID_EVIDENCE_KEY","图片键无效");Path p=root.resolve(key).normalize();if(!p.getParent().equals(root)||Files.isSymbolicLink(p))throw error(400,"INVALID_EVIDENCE_KEY","图片路径无效");return p;}
 /** 文件头识别白名单，不信任扩展名或客户端Content-Type，不解压图片以避免内存膨胀。 */
 private String media(byte[] b){if(b.length>=24&&(b[0]&255)==137&&b[1]==80&&b[2]==78&&b[3]==71&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10)return "image/png";if(b.length>=4&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255)return "image/jpeg";if(b.length>=16&&new String(b,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(b,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))return "image/webp";throw error(400,"INVALID_EVIDENCE_TYPE","仅支持PNG、JPEG、WebP图片");}
 private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 private static BusinessException error(int status,String code,String message){return new BusinessException(status,code,message);}
 private static BusinessException unavailable(){return error(503,"EVIDENCE_STORAGE_UNAVAILABLE","图片存储暂时不可用");}
}
