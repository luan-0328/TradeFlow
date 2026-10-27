package com.tradeflow.aftersale.application;

import com.tradeflow.aftersale.infrastructure.*;
import com.tradeflow.common.BusinessException;
import com.tradeflow.contracts.TradingContracts;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 争议只协调原售后；裁定、审核状态、原号任务和审计同事务，不生成未知退款的新执行号。 */
@Service
public class GovernanceService {
    public record Apply(@Pattern(regexp="as_[a-f0-9]{32}") @NotBlank String aftersaleNo,@NotBlank @Size(max=1000) String reason) { }
    public record Decision(@Pattern(regexp="APPROVE|REJECT|UPHOLD") @NotBlank String decision,@NotBlank @Size(max=1000) String reason,@Min(1) long version) { }
    private final GovernanceMapper mapper;private final AftersaleStore db;private final Authority authority;private final TransactionTemplate tx;
    public GovernanceService(GovernanceMapper mapper,AftersaleStore db,Authority authority,TransactionTemplate tx){this.mapper=mapper;this.db=db;this.authority=authority;this.tx=tx;}
    /** 申请必须本人原售后；行锁串行化相同售后的介入申请，摘要避免同键换参。 */
    public Map<String,Object> apply(Jwt jwt,String key,Apply body){var user=authority.check(jwt,"user");if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,64}"))throw fail("INVALID_IDEMPOTENCY_KEY");String hash=TradingContracts.sha256(body.aftersaleNo()+":"+body.reason().strip()),no="dsp_"+UUID.randomUUID().toString().replace("-","");return tx.execute(s->{var a=db.get(body.aftersaleNo(),true);if(a==null||!user.userId().equals(a.userId()))throw missing();mapper.create(no,a.aftersaleNo(),user.userId(),key,hash,body.reason().strip());var old=mapper.byKey(user.userId(),key);if(!hash.equals(old.get("request_hash")))throw fail("IDEMPOTENCY_CONFLICT");mapper.audit(user.userId(),a.aftersaleNo(),"DISPUTE_OPEN","申请平台介入");return old;});}
    /** 买家仅看本人；平台列表包含依据与裁定，不包含收货资料。 */
    public Map<String,Object> list(Jwt jwt,boolean admin,int page,int size){var user=authority.check(jwt,admin?"admin":"user");page(page,size);String owner=admin?null:user.userId();return tx.execute(s->Map.of("items",mapper.disputes(owner,size,(long)(page-1)*size),"total",mapper.countDisputes(owner),"page",page,"pageSize",size));}
    /** 平台只能在原待审核阶段替代审核；已执行/已终止授权不得逆转，只能保持并说明后续处理。 */
    public Map<String,Object> decide(Jwt jwt,String no,Decision body){var actor=authority.check(jwt,"admin");var snapshot=mapper.dispute(no);if(snapshot==null)throw missing();return tx.execute(s->{var a=db.get((String)snapshot.get("aftersale_no"),true);var dispute=mapper.lockDispute(no);if(a==null||dispute==null)throw missing();
        if("DECIDED".equals(dispute.get("status"))){if(!body.decision().equals(dispute.get("decision"))||!body.reason().strip().equals(dispute.get("decision_reason")))throw fail("DECISION_CONFLICT");return dispute;}
        if(!"UPHOLD".equals(body.decision())){
            int changed="APPROVE".equals(body.decision())?mapper.approve(a.aftersaleNo()):mapper.reject(a.aftersaleNo());if(changed!=1)throw fail("DISPUTE_STATE_CONFLICT");
            if("REJECT".equals(body.decision()))db.task(a.aftersaleNo(),"TERMINATE");
            else if(!"RETURN_REFUND".equals(a.type()))db.task(a.aftersaleNo(),"REFUND");
        }
        if(mapper.decide(no,body.version(),body.decision(),body.reason().strip(),actor.userId())!=1)throw fail("VERSION_CONFLICT");mapper.audit(actor.userId(),a.aftersaleNo(),"PLATFORM_"+body.decision(),body.reason().strip());return mapper.dispute(no);
    });}
    /** 后端真正分页人工任务，避免前端分页掩盖最多100条的截断。 */
    public Map<String,Object> tasks(Jwt jwt,int page,int size){authority.check(jwt,"admin");page(page,size);return tx.execute(s->Map.of("items",mapper.tasks(size,(long)(page-1)*size),"total",mapper.countTasks(),"page",page,"pageSize",size));}
    private void page(int p,int s){if(p<1||s<1||s>100)throw fail("INVALID_PAGE");}
    private BusinessException fail(String code){return new BusinessException(409,code,"状态已变化或请求不合法，请刷新核对");}
    private BusinessException missing(){return new BusinessException(404,"DISPUTE_NOT_FOUND","争议或售后不存在");}
}
