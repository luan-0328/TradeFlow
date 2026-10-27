/** 前端只使用外部业务DTO，ID保持字符串，金额保持整数分，未知后台字段需显式读取。 */
export interface User { userId:string; username:string; displayName:string; status:string; roles:string[] }
export interface Capabilities { userId:string; roles:string[]; merchantIds:string[]; merchantRoles:Record<string,'ADMIN'|'OPERATOR'>; allowedActions:string[] }
export interface Page<T> {items:T[];page:number;pageSize:number;total:number}
export interface Sku {skuId:string;skuCode:string;specification:string;priceCents:number;status:string;version:number;priceVersion:number;availableQty:number;reservedQty:number}
export interface Product {productId:string;merchantId:string;name:string;description:string;status:string;version:number;skus:Sku[]}
export interface OrderUnit {unitNo:number;paidAmountCents:number;reservedRefundCents:number;succeededRefundCents:number;remainingRefundCents:number;returnState:string;hasActiveAftersale:boolean}
export interface OrderItem {orderItemId:string;skuId:string;productName:string;specification:string;unitPriceCents:number;quantity:number;units:OrderUnit[]}
export interface Order {orderNo:string;paymentNo:string;status:string;stockSyncStatus:string;fulfillmentLock:string;amountCents:number;currency:string;payDeadline:string;createdAt:string;items:OrderItem[];allowedActions:string[];shipment?:{carrier:string;trackingNo:string}|null}
export interface Allocation {orderItemId:string;unitNo:number;amountCents:number}
export interface Aftersale {aftersaleNo:string;orderNo:string;userId:string;merchantId:string;type:string;reason:string;status:string;refundNo:string;refundStatus:string;amountCents:number|null;currency:string|null;lastError:string|null;allowedActions:string[]}
export interface Preview {orderNo:string;userId:string;merchantId:string;type:string;amountCents:number;currency:string;allocations:Allocation[];units:{orderItemId:string;unitNo:number;skuId:string;paidAmountCents:number;succeededRefundCents:number;reservedRefundCents:number;remainingCents:number;activeAftersaleNo:string|null;returnState:string}[]}
export type DataRow=Record<string,unknown>
export interface Materials {returnDeadline:string|null;receiptNo:string|null;allocations:Allocation[];evidence:{objectKey:string;purpose:string}[];shipment:DataRow|null;inspection:DataRow|null}
export interface Payment {paymentNo:string;orderNo:string;userId:string;amountCents:number;currency:string;status:string;closeIntent:string;channelTransactionNo:string|null;succeededAt:string|null;lastError:string|null}
