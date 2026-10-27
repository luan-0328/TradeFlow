package com.tradeflow.paymentsimulator.api;
import com.tradeflow.common.*;
import com.tradeflow.contracts.RefundContracts.*;
import com.tradeflow.paymentsimulator.application.SimulatorRefundService;
import com.tradeflow.paymentsimulator.infrastructure.SimulatorSecurity;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
/** 退款通道独立传输入口，与支付共享服务凭据但不共享资金事实或场景。 */
@RestController
public class SimulatorRefundController {
 private final SimulatorRefundService service;private final SimulatorSecurity security;
 public SimulatorRefundController(SimulatorRefundService service,SimulatorSecurity security){this.service=service;this.security=security;}
 /** 模拟响应超时在资金事务提交之后返回，原号查询可恢复真实事实。 */
 @PostMapping("/channel/v1/refunds") public ApiResponse<ChannelFact> refund(@RequestBody ChannelCommand c,HttpServletRequest r){security.channel(r);var result=service.refund(c);if(result.responseTimeout())throw new BusinessException(504,"CHANNEL_RESPONSE_TIMEOUT","退款响应超时，请按原退款号查询");return WebResponses.ok(r,result.fact());}
 /** 查询不创建退款，不将不存在伪造为失败或成功。 */
 @GetMapping("/channel/v1/refunds/{no}") public ApiResponse<ChannelFact> query(@PathVariable String no,HttpServletRequest r){security.channel(r);return WebResponses.ok(r,service.query(no));}
 public record Scenario(String refundNo,String mode,String actorId,String reason) { }
 /** 独立dev管理员token及操作原因限制退款故障配置。 */
 @PostMapping("/simulator-admin/v1/refund-scenarios") public ApiResponse<Map<String,Boolean>> scenario(@RequestBody Scenario c,HttpServletRequest r){security.admin(r);service.configure(c.refundNo(),c.mode(),c.actorId(),c.reason(),(String)r.getAttribute("requestId"));return WebResponses.ok(r,Map.of("configured",true));}
}
