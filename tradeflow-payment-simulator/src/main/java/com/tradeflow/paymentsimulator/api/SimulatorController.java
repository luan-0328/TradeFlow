package com.tradeflow.paymentsimulator.api;
import com.tradeflow.common.*;
import com.tradeflow.contracts.PaymentContracts.*;
import com.tradeflow.paymentsimulator.application.SimulatorService;
import com.tradeflow.paymentsimulator.infrastructure.SimulatorSecurity;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
/** 通道接口不接受用户身份头，所有响应使用公共信封。 */
@RestController
public class SimulatorController {
 private final SimulatorService service;private final SimulatorSecurity security;
 public SimulatorController(SimulatorService service,SimulatorSecurity security){this.service=service;this.security=security;}
 /** 同号pay由数据库串行；模拟超时发生在事实和任务提交之后，不回滚支付成功。 */
 @PostMapping("/channel/v1/payments") public ApiResponse<ChannelFact> pay(@RequestBody ChannelCommand command,HttpServletRequest r){
  security.channel(r);var result=service.pay(command);
  if(result.responseTimeout())throw new BusinessException(504,"CHANNEL_RESPONSE_TIMEOUT","通道响应超时，请按原支付号查询");
  return WebResponses.ok(r,result.fact());
 }
 /** 路径与body支付号必须一致，不能借路径关闭其他绑定。 */
 @PostMapping("/channel/v1/payments/{paymentNo}/close") public ApiResponse<ChannelFact> close(@PathVariable String paymentNo,@RequestBody ChannelCommand command,HttpServletRequest r){
  security.channel(r);if(!paymentNo.equals(command.paymentNo()))throw new BusinessException(409,"CHANNEL_BINDING_CONFLICT","路径和支付号不一致");return WebResponses.ok(r,service.close(command));
 }
 /** 查询不产生交易、不建立关闭屏障；查无记录是404。 */
 @GetMapping("/channel/v1/payments/{paymentNo}") public ApiResponse<ChannelFact> query(@PathVariable String paymentNo,HttpServletRequest r){security.channel(r);return WebResponses.ok(r,service.query(paymentNo));}
 public record ScenarioCommand(String paymentNo,String mode,String actorId,String reason) { }
 /** 场景一次绑定并追加审计，客户端不得将该入口当作资金状态修改入口。 */
 @PostMapping("/simulator-admin/v1/scenarios") public ApiResponse<java.util.Map<String,Boolean>> scenario(@RequestBody ScenarioCommand c,HttpServletRequest r){security.admin(r);service.configure(c.paymentNo(),c.mode(),c.actorId(),c.reason(),(String)r.getAttribute("requestId"));return WebResponses.ok(r,java.util.Map.of("configured",true));}
}
