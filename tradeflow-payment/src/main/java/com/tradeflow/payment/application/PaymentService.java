package com.tradeflow.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeflow.common.*;
import com.tradeflow.contracts.AuthContext;
import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.payment.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 支付用例与可恢复执行器；资金事实事务内一次应用，网络调用一律在事务外，未知先查原号。 */
@Service
public class PaymentService {
    private final PaymentMapper db;private final PaymentRemote remote;private final TransactionTemplate tx;private final ObjectMapper json;
    private final String orderToken,callbackSecret,owner=TraceIds.newId();
    public PaymentService(PaymentMapper db,PaymentRemote remote,TransactionTemplate tx,ObjectMapper json,Environment env){this.db=db;this.remote=remote;this.tx=tx;this.json=json;orderToken=env.getRequiredProperty("tradeflow.internal.order-token");callbackSecret=env.getRequiredProperty("tradeflow.callback-secret");if(orderToken.isBlank()||callbackSecret.isBlank())throw new IllegalStateException("支付内部凭据不能为空");}
    /** 本人支付使用订单权威金额；先保存稳定意图与幂等键，后台提交，202不代表扣款成功。 */
    public View submit(Jwt jwt,String key,String orderNo){
        AuthContext user=authorize(jwt,false);PaymentRemote.safe(orderNo);
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,64}"))throw error(400,"INVALID_IDEMPOTENCY_KEY","必须提供有效幂等键");
        Intent intent=remote.intent(orderNo);validateIntent(intent,orderNo,null);
        if(!user.userId().equals(intent.userId()))throw error(404,"ORDER_NOT_FOUND","订单不存在");
        return tx.execute(t->{
            db.ensureTask(intent.paymentNo());db.lockTask(intent.paymentNo());
            Map<String,Object> existing=db.find(intent.paymentNo());
            if(existing==null && (!"PENDING_PAYMENT".equals(intent.orderStatus()) || intent.payDeadline()==null || !intent.payDeadline().isAfter(Instant.now())))throw error(409,"ORDER_NOT_PAYABLE","订单当前不可支付");
            db.ensure(intent);Map<String,Object> row=required(db.lock(intent.paymentNo()));bind(row,intent);
            db.idempotency(user.userId(),key,hash(orderNo.getBytes(StandardCharsets.UTF_8)),intent.paymentNo());
            Map<String,Object> saved=db.idem(user.userId(),key);
            if(!intent.paymentNo().equals(s(saved,"business_no")) || !hash(orderNo.getBytes(StandardCharsets.UTF_8)).equals(s(saved,"request_hash")))throw error(409,"IDEMPOTENCY_CONFLICT","同幂等键不能绑定不同订单");
            if(!terminal(s(row,"status")) && (!"PENDING_PAYMENT".equals(intent.orderStatus()) || intent.payDeadline()==null || !intent.payDeadline().isAfter(Instant.now())))throw error(409,"ORDER_NOT_PAYABLE","订单当前不可支付");
            if("YES".equals(s(row,"review_required")))throw error(409,"PAYMENT_MANUAL_REVIEW","支付事实需人工核验");
            if("YES".equals(s(row,"close_intent")))throw error(409,"PAYMENT_CLOSING","支付关闭意图已成立");
            return view(row);
        });
    }
    /** 本人查询仍校验最新账号版本，支付归属以已持久权威意图为准。 */
    public View lookupRequest(Jwt jwt,String key,String orderNo){
        AuthContext user=authorize(jwt,false);PaymentRemote.safe(orderNo);
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,64}"))throw error(400,"INVALID_IDEMPOTENCY_KEY","需要原支付幂等键");
        // 身份RPC在本地查询之前完成；原键绑定和权威支付同一SQL快照，查不到仍可能有未提交的请求。
        var row=db.acceptedRequest(user.userId(),key,orderNo);return row==null?null:view(row);
    }
    /** 本人查询仍校验最新账号版本，支付归属以已持久权威意图为准。 */
    public View detail(Jwt jwt,String no){AuthContext user=authorize(jwt,false);Map<String,Object> row=required(db.find(PaymentRemote.safe(no)));if(!user.userId().equals(s(row,"user_id")))throw error(404,"PAYMENT_NOT_FOUND","支付不存在");return view(row);}
    /** 独立order凭据先验证，再读取权威订单；先到close也创建同一唯一支付意图。 */
    public View close(String caller,String token,String no,CloseCommand command){
        internal(caller,token);PaymentRemote.safe(no);if(command==null)throw error(400,"INVALID_ARGUMENT","关闭命令不能为空");
        Intent intent=remote.intent(PaymentRemote.safe(command.orderNo()));validateIntent(intent,command.orderNo(),no);
        return tx.execute(t->{db.ensureTask(no);db.lockTask(no);db.ensure(intent);Map<String,Object> row=required(db.lock(no));bind(row,intent);requireTrusted(row);db.closing(no);
            if(!terminal(s(row,"status")) && !"YES".equals(s(row,"close_intent")))db.closeTask(no);return view(required(db.lock(no)));});
    }
    /** 只读本地已确认事实；PENDING/UNKNOWN都不能解释为通道已关闭。 */
    public View internalDetail(String caller,String token,String no){internal(caller,token);return view(requireTrusted(required(db.find(PaymentRemote.safe(no)))));}
    /** 订单主动查询真实通道事实；无记录保持未决，绝不据此伪造关闭成功。 */
    public View queryChannel(String caller,String token,String no){internal(caller,token);PaymentRemote.safe(no);required(db.find(no));ChannelFact fact=remote.query(no);
        if(fact!=null)tx.executeWithoutResult(t->apply(required(db.lock(no)),fact));return view(requireTrusted(required(db.find(no))));}
    /** 平台只读支付与退款成功资金事实分页；资金结果仅由真实通道事实应用，不能通过该查询入口人工改写。 */
    public Map<String,Object> ledger(Jwt jwt,int page,int size){authorize(jwt,true);if(page<1||size<1||size>100)throw error(400,"INVALID_ARGUMENT","分页参数不合法");return Map.of("page",page,"pageSize",size,"total",db.ledgerCount(),"items",db.ledgerPage(size,(long)(page-1)*size));}
    /** HMAC先于JSON解析及通知号占用；原始字节摘要使同号异参不能借重试覆盖证据。 */
    public Map<String,Boolean> callback(byte[] body,String timestamp,String nonce,String signature){
        if(body==null||body.length>65536)throw error(413,"CALLBACK_TOO_LARGE","回调正文过大");verify(body,timestamp,nonce,signature);
        Callback callback;try{callback=json.readValue(body,Callback.class);}catch(Exception e){throw error(400,"INVALID_CALLBACK","回调格式不合法");}
        if(callback==null||callback.fact()==null)throw error(400,"INVALID_CALLBACK","回调事实不能为空");
        if(callback.notificationNo()==null||!callback.notificationNo().matches("[A-Za-z0-9._:-]{1,64}"))throw error(400,"INVALID_CALLBACK","通知编号格式不合法");PaymentRemote.safe(callback.fact().paymentNo());String digest=hash(body);
        try{tx.executeWithoutResult(t->{
            // 先锁同一支付意图，合法并发重复通知在此串行，验真失败永远不会到达去重表。
            Map<String,Object> row=required(db.lock(callback.fact().paymentNo()));
            Map<String,Object> saved=db.notification(callback.notificationNo());
            if(saved!=null){if(!digest.equals(s(saved,"payload_hash")))throw error(409,"NOTIFICATION_CONFLICT","同通知编号的内容不一致");return;}
            boolean applied=apply(row,callback.fact());db.notificationInsert(callback.notificationNo(),callback.fact().paymentNo(),digest);
            if(!applied)db.quarantine(callback.notificationNo());
        });}catch(org.springframework.dao.DuplicateKeyException duplicate){
            tx.executeWithoutResult(t->{Map<String,Object> saved=db.notification(callback.notificationNo());if(saved==null||!digest.equals(s(saved,"payload_hash")))throw error(409,"NOTIFICATION_CONFLICT","同通知编号的内容不一致");});
        }return Map.of("ack",true);
    }
    /** 每次仅领取一个到期任务，租约过期可接管；RPC后只有原claim_version仍有效才能推进步骤。 */
    @Scheduled(fixedDelayString="${tradeflow.worker-delay-ms:1000}")
    public void recover(){
        Job job=tx.execute(t->{String no=db.dueTask();if(no==null)return null;db.claimTask(no,owner);Map<String,Object> task=db.lockTask(no);Map<String,Object> row=required(db.lock(no));
            if("YES".equals(s(row,"review_required"))){finishTask(no,n(task,"claim_version"),"MANUAL_REVIEW",s(task,"step"),"支付事实矛盾，需人工核验");return null;}
            if(terminal(s(row,"status"))){finishTask(no,n(task,"claim_version"),"SUCCEEDED",s(task,"step"),null);return null;}
            // 达到本轮恢复上限保留全部意图和事实，不按失败释放任何权益。
            if(n(task,"attempt_count")>30){finishTask(no,n(task,"claim_version"),"MANUAL_REVIEW",s(task,"step"),"恢复预算耗尽，需人工核验");return null;}
            String step="YES".equals(s(row,"close_intent")) && !"QUERY".equals(s(task,"step"))?"CLOSE":s(task,"step");
            // 写前将恢复步骤改为QUERY；进程在扣款请求前后崩溃均先查询原号，避免盲目重扣。
            if("SUBMIT".equals(step)||"CLOSE".equals(step))db.beforeSubmit(no);
            return new Job(no,n(task,"claim_version"),step,new ChannelCommand(no,s(row,"order_no"),n(row,"amount_cents"),s(row,"currency")));
        });
        if(job==null)return;
        try{
            if("SUBMIT".equals(job.step())){
                Intent latest=remote.intent(job.command().orderNo());validateIntent(latest,job.command().orderNo(),job.no());
                if(!"PENDING_PAYMENT".equals(latest.orderStatus()) || latest.payDeadline()==null || !latest.payDeadline().isAfter(Instant.now())){
                    tx.executeWithoutResult(t->{if(db.fenceTask(job.no(),job.claim(),owner)==null)return;Map<String,Object> row=required(db.lock(job.no()));bind(row,latest);db.closing(job.no());if(db.closeOwnedTask(job.no(),job.claim(),owner)!=1)throw new IllegalStateException("支付关闭转换租约失效，本地应用回滚");});return;
                }
                if(latest.amountCents()!=job.command().amountCents() || !latest.currency().equals(job.command().currency()))throw error(409,"INVALID_CHANNEL_FACT","订单权威金额发生矛盾");
            }
            ChannelFact fact="QUERY".equals(job.step())?remote.query(job.no()):remote.command(job.command(),"CLOSE".equals(job.step()));
            tx.executeWithoutResult(t->{if(db.fenceTask(job.no(),job.claim(),owner)==null)return;Map<String,Object> row=required(db.lock(job.no()));
                if(fact==null){finishTask(job.no(),job.claim(),"READY","YES".equals(s(row,"close_intent"))?"CLOSE":"SUBMIT",null);return;}
                boolean valid=apply(row,fact);String state=!valid?"MANUAL_REVIEW":terminal(fact.status())?"SUCCEEDED":"READY";
                finishTask(job.no(),job.claim(),state,"YES".equals(s(row,"close_intent"))?"CLOSE":"QUERY",valid?null:"通道终态矛盾，需人工核验");
            });
        }catch(Exception failure){tx.executeWithoutResult(t->{if(db.fenceTask(job.no(),job.claim(),owner)==null)return;required(db.lock(job.no()));boolean invalid=failure instanceof BusinessException b && "INVALID_CHANNEL_FACT".equals(b.code());
                if(invalid)db.issue(job.no(),"通道事实绑定或格式不合法，需人工核验");else db.unknown(job.no());
                finishTask(job.no(),job.claim(),invalid?"MANUAL_REVIEW":"READY","QUERY",invalid?"通道事实校验失败":"通道结果未知，先查原支付号");});}
    }
    /** 可信响应、主动查询和已验真回调的唯一状态应用器；成功流水、额度与通知同一事务提交。 */
    private boolean apply(Map<String,Object> row,ChannelFact fact){
        if(fact==null||!s(row,"payment_no").equals(fact.paymentNo())||!s(row,"order_no").equals(fact.orderNo())||n(row,"amount_cents")!=fact.amountCents()||!"CNY".equals(fact.currency())||fact.channelVersion()<1||fact.updatedAt()==null||(fact.channelTransactionNo()!=null&&!fact.channelTransactionNo().matches("[A-Za-z0-9:_-]{1,64}"))||!Set.of("PENDING","PROCESSING","UNKNOWN","SUCCEEDED","FAILED","CLOSED").contains(fact.status()==null?"":fact.status())||("SUCCEEDED".equals(fact.status())&&(fact.succeededAt()==null||fact.channelTransactionNo()==null||fact.channelTransactionNo().isBlank())))
            throw error(409,"INVALID_CHANNEL_FACT","通道事实绑定或格式不合法");
        if("YES".equals(s(row,"review_required")))return false;
        String current=s(row,"status");long version=n(row,"channel_version");
        if(s(row,"channel_transaction_no")!=null&&!Objects.equals(s(row,"channel_transaction_no"),fact.channelTransactionNo())){conflict(row,fact,"通道交易号绑定变化");return false;}
        if(terminal(current)&&!current.equals(fact.status())){
            // 较旧非终态仅忽略；相反终态即使版本较旧也保留严重矛盾证据，不覆盖真实已支付状态。
            if(!terminal(fact.status())&&fact.channelVersion()<version)return true;
            conflict(row,fact,"通道终态矛盾");return false;
        }
        if(fact.channelVersion()<version)return true;
        if(fact.channelVersion()==version){
            if(!current.equals(fact.status())||!Objects.equals(s(row,"channel_transaction_no"),fact.channelTransactionNo())||!Objects.equals(time(row,"succeeded_at"),millis(fact.succeededAt()))||!Objects.equals(time(row,"channel_updated_at"),millis(fact.updatedAt()))){conflict(row,fact,"同通道版本事实不一致");return false;}return true;
        }
        if(terminal(current)){
            if(!Objects.equals(s(row,"channel_transaction_no"),fact.channelTransactionNo())||!Objects.equals(time(row,"succeeded_at"),millis(fact.succeededAt()))){conflict(row,fact,"通道终态证据变更");return false;}
            db.fact(fact);return true;
        }
        db.fact(fact);
        if("SUCCEEDED".equals(fact.status())){db.ledger(fact);db.refundAccount(fact);}
        if(terminal(fact.status())){com.tradeflow.observability.BusinessLog.afterCommit("PAYMENT_FACT_APPLIED",Map.of("paymentNo",fact.paymentNo(),"orderNo",fact.orderNo(),"taskId",fact.paymentNo(),"state",fact.status()));String id="evt_"+TraceIds.newId();Event event=new Event(id,fact);try{db.outbox(id,fact.paymentNo(),fact.channelVersion(),"Payment"+switch(fact.status()){case "SUCCEEDED"->"Succeeded";case "FAILED"->"Failed";default->"Closed";},fact.orderNo(),json.writeValueAsString(event));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("支付事件序列化失败",e);}}
        return true;
    }
    /** 矛盾事实只留下可审计人工处置标记，禁止重写终态或自动伪造退款。 */
    private void conflict(Map<String,Object> row,ChannelFact fact,String reason){String note=reason+"：本地="+s(row,"status")+"/"+n(row,"channel_version")+"，通道="+fact.status()+"/"+fact.channelVersion()+"，交易="+fact.channelTransactionNo();db.issue(fact.paymentNo(),note);db.audit(fact.paymentNo(),TraceIds.newId(),note);}
    /** 撤销版本和角色以用户服务最新事实为准；身份RPC不可进入本地事务。 */
    private AuthContext authorize(Jwt jwt,boolean admin){if(jwt==null)throw error(401,"UNAUTHORIZED","需要有效登录令牌");AuthContext auth=remote.context(jwt.getSubject());Object version=jwt.getClaims().get("token_version");if(!jwt.getSubject().equals(auth.userId())||!"ACTIVE".equals(auth.status())||!(version instanceof Number n)||n.longValue()!=auth.tokenVersion())throw error(401,"TOKEN_REVOKED","登录状态已失效");if(auth.roles()==null||!auth.roles().contains(admin?"PLATFORM_ADMIN":"USER"))throw error(403,"FORBIDDEN","缺少所需权限");return auth;}
    /** 平台消息恢复入口复用最新用户角色和撤销版本校验；必须在运维事务前调用。 */
    public void authorizePlatform(Jwt jwt){authorize(jwt,true);}
    /** 对账已持action围栏后加入同一本地事务，复用唯一事实应用；只在核验成功后补缺失唯一流水，不重扣款。 */
    public boolean reconcileApply(ChannelFact fact,String actionNo,boolean repairLedger){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("对账资金修复必须处于已围栏事务");
        var row=required(db.lock(fact.paymentNo()));if(!apply(row,fact))return false;
        if(repairLedger&&"SUCCEEDED".equals(fact.status())&&db.reconciliationLedgerCount(fact.paymentNo())==0){
            // 旧版本事实可能被统一应用器忽略；补流水前仍须核对已锁定的当前成功证据。
            var current=required(db.lock(fact.paymentNo()));
            if(!"SUCCEEDED".equals(s(current,"status"))||!Objects.equals(s(current,"channel_transaction_no"),fact.channelTransactionNo())||!Objects.equals(time(current,"succeeded_at"),millis(fact.succeededAt()))){db.issue(fact.paymentNo(),"对账补流水成功证据矛盾");db.audit(fact.paymentNo(),actionNo,"补流水证据冲突，保留原事实");return false;}
            db.ledger(new ChannelFact(fact.paymentNo(),s(current,"order_no"),n(current,"amount_cents"),s(current,"currency"),"SUCCEEDED",s(current,"channel_transaction_no"),n(current,"channel_version"),time(current,"channel_updated_at"),time(current,"succeeded_at")));
        }
        db.audit(fact.paymentNo(),actionNo,"对账统一事实应用与受控流水补记，action="+actionNo);return true;
    }
    /** order专用凭据常量时间比较，不能接受payment或channel凭据代替。 */
    public void internal(String caller,String token){if(!"order".equals(caller)||token==null||!MessageDigest.isEqual(orderToken.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw error(403,"FORBIDDEN","内部调用凭据无效");}
    /** 金额、币种及双业务号由订单权威绑定；关闭也不能凭调用方自报金额建单。 */
    private void validateIntent(Intent i,String order,String payment){if(i==null||!order.equals(i.orderNo())||i.paymentNo()==null||i.userId()==null||i.amountCents()<=0||!"CNY".equals(i.currency())||(payment!=null&&!payment.equals(i.paymentNo())))throw error(409,"INVALID_PAYMENT_INTENT","订单支付意图绑定不一致");PaymentRemote.safe(i.paymentNo());}
    /** 持锁校验原意图，唯一键冲突不可把其他订单的资金记录借给当前请求。 */
    private void bind(Map<String,Object> row,Intent i){if(!i.orderNo().equals(s(row,"order_no"))||!i.paymentNo().equals(s(row,"payment_no"))||!i.userId().equals(s(row,"user_id"))||i.amountCents()!=n(row,"amount_cents")||!i.currency().equals(s(row,"currency")))throw error(409,"PAYMENT_INTENT_CONFLICT","支付意图绑定不一致");}
    /** 原始UTF8正文、秒级时间戳和nonce共同签名，五分钟窗口内常量时间比较。 */
    private void verify(byte[] body,String timestamp,String nonce,String signature){try{long at=Long.parseLong(timestamp);long now=Instant.now().getEpochSecond();if(at<now-300||at>now+300||nonce==null||!nonce.matches("[A-Za-z0-9_-]{1,128}")||signature==null||!signature.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException();Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(callbackSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));if(!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature)))throw new IllegalArgumentException();}catch(Exception e){throw error(401,"INVALID_CALLBACK_SIGNATURE","回调验真失败");}}
    /** 完成前再次以数据库时钟检查有效租约；零行表示权利已失效，必须回滚同事务资金效果。 */
    private void finishTask(String no,long claim,String state,String step,String error){
        if(db.finishTask(no,claim,owner,state,step,error)!=1)throw new IllegalStateException("支付恢复租约失效，本地应用回滚");
    }
    private record Job(String no,long claim,String step,ChannelCommand command) { }
    /** 人工冻结的原终态不能作为订单释放库存的权威证据；内部调用明确拒绝自动推进。 */
    private static Map<String,Object> requireTrusted(Map<String,Object> row){
        if("YES".equals(s(row,"review_required")))throw error(409,"PAYMENT_MANUAL_REVIEW","支付事实存在矛盾，需要人工核验");
        return row;
    }
    private static boolean terminal(String status){return Set.of("SUCCEEDED","FAILED","CLOSED").contains(status);}
    private static Map<String,Object> required(Map<String,Object> row){if(row==null)throw error(404,"PAYMENT_NOT_FOUND","支付不存在");return row;}
    private static View view(Map<String,Object> r){return new View(s(r,"payment_no"),s(r,"order_no"),s(r,"user_id"),n(r,"amount_cents"),s(r,"currency"),s(r,"status"),s(r,"close_intent"),s(r,"channel_transaction_no"),time(r,"succeeded_at"),s(r,"last_error"));}
    private static String s(Map<String,Object> r,String k){Object v=r.get(k);return v==null?null:v.toString();}
    private static long n(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
    private static Instant time(Map<String,Object> r,String k){Object value=r.get(k);if(value==null)return null;if(value instanceof Timestamp v)return v.toInstant();if(value instanceof LocalDateTime v)return v.toInstant(ZoneOffset.UTC);if(value instanceof Instant v)return v;throw new IllegalStateException("未知数据库时间类型");}
    private static Instant millis(Instant instant){return instant==null?null:instant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}
    private static String hash(byte[] body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}catch(Exception e){throw new IllegalStateException(e);}}
    private static BusinessException error(int status,String code,String message){return new BusinessException(status,code,message);}
}
