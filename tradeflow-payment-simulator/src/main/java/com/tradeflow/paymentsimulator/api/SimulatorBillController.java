package com.tradeflow.paymentsimulator.api;

import com.tradeflow.common.ApiResponse;
import com.tradeflow.contracts.ReconciliationContracts.*;
import com.tradeflow.paymentsimulator.application.SimulatorBillService;
import com.tradeflow.paymentsimulator.infrastructure.SimulatorSecurity;
import com.tradeflow.web.WebResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 冻结账单只能由payment服务读取；开发投影管理复用独立管理凭证与环境限制。 */
@RestController
public class SimulatorBillController {
 private final SimulatorBillService service;private final SimulatorSecurity security;
 public SimulatorBillController(SimulatorBillService service,SimulatorSecurity security){this.service=service;this.security=security;}
 /** 原请求号冻结重放返回同一快照，所有身份验证在数据库事务之前。 */
 @PostMapping("/channel/v1/bill-snapshots")
 public ApiResponse<BillManifest> freeze(@RequestBody BillRequest body,HttpServletRequest r){security.channel(r);return WebResponses.ok(r,service.freeze(body));}
 /** 清单只读且不重新计算，故障投影也在冻结时固定。 */
 @GetMapping("/channel/v1/bill-snapshots/{snapshot}")
 public ApiResponse<BillManifest> manifest(@PathVariable String snapshot,HttpServletRequest r){security.channel(r);return WebResponses.ok(r,service.manifest(snapshot));}
 /** 页游标由冻结位置决定，最大一百行，客户端必须核验总数和摘要。 */
 @GetMapping("/channel/v1/bill-snapshots/{snapshot}/lines")
 public ApiResponse<BillPage> page(@PathVariable String snapshot,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int pageSize,HttpServletRequest r){security.channel(r);return WebResponses.ok(r,service.page(snapshot,after,pageSize));}
 public record ProjectionCommand(String requestNo,String mode,String actorId,String reason){}
 /** 只影响指定请求的账单投影，禁止改资金；配置人与原因追加审计。 */
 @PostMapping("/simulator-admin/v1/bill-projections")
 public ApiResponse<java.util.Map<String,Boolean>> projection(@RequestBody ProjectionCommand c,HttpServletRequest r){security.admin(r);service.configure(c.requestNo(),c.mode(),c.actorId(),c.reason(),(String)r.getAttribute("requestId"));return WebResponses.ok(r,java.util.Map.of("configured",true));}
}
