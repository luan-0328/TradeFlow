"""部署逐表/逐列权限快照，来自Prepare-Dev；改业务契约时同步维护。"""
def runtime_grants(service, env):
    """新部署账号仅授予本库必需写列，不授予DDL、流水修改或DELETE。"""
    db = 'trade_' + service
    app = env[f'TRADEFLOW_{service.upper()}_DB_USER']
    statements = []
    def mysql(admin, sql):
        if sql.startswith('SELECT '): return '0'
        statements.append(sql)
    admin = None
    mutable = {
        'user': ['user_account', 'user_role', 'merchant_member', 'login_rate_limit', 'login_entry_window', 'authorization_guard'],
        'product': ['product', 'sku', 'inventory', 'inventory_receipt', 'idempotency_request', 'product_quote', 'reservation_header', 'inventory_condition'],
        'order': ['orders', 'idempotency_request', 'process_task', 'order_creation_intent', 'order_item', 'order_item_unit', 'refund_authorization', 'refund_flow_task', 'return_stock_task'],
        'payment': ['payment_order', 'channel_notification', 'idempotency_request', 'process_task'],
        'payment_simulator': [],
        'aftersale': ['aftersale', 'aftersale_task', 'aftersale_refund_attempt'],
        'settlement': []
    }[service]
    immutable = {
        'user': ['audit_log', 'merchant', 'merchant_application', 'user_address', 'user_notification'],
        'product': ['quote_item', 'receipt_item', 'inventory_ledger', 'audit_log', 'reservation_item', 'unshipped_restock', 'return_restock', 'return_restock_item', 'inventory_condition_operation', 'product_image'],
        'order': ['order_address', 'order_status_log', 'audit_log', 'order_payment_fact', 'order_shipment', 'order_refund_fact', 'refund_allocation', 'order_return_receipt', 'order_return_disposition', 'merchant_shipping_rule', 'order_charge_snapshot', 'trade_group', 'trade_group_child', 'shipping_rule_command', 'order_notification_task'],
        'payment': ['payment_ledger', 'audit_log', 'refund_account', 'refund_source', 'refund_order', 'outbox_event', 'reconciliation_run', 'reconciliation_issue', 'reconciliation_action', 'reconciliation_stage_line', 'reconciliation_observation', 'reconciliation_command'],
        'payment_simulator': ['channel_transaction', 'fault_scenario', 'process_task', 'callback_task', 'audit_log', 'channel_refund', 'refund_fault_scenario', 'refund_process_task', 'refund_callback_task', 'bill_snapshot', 'bill_snapshot_line', 'bill_projection_scenario'],
        'aftersale': ['aftersale_idempotency', 'aftersale_audit', 'aftersale_allocation', 'aftersale_return_shipment', 'aftersale_return_inspection', 'aftersale_return_disposition', 'evidence_file', 'aftersale_evidence', 'platform_dispute'],
        'settlement': ['settlement_task', 'settlement_account', 'settlement_item', 'settlement_fact', 'settlement_ledger', 'settlement_batch', 'settlement_reconciliation', 'settlement_audit']
    }[service]
    # U1-U4新增表权限按可变列收紧；ON DUPLICATE自赋值也只开放对应唯一键列，账本不开放UPDATE/DELETE。
    upgrade_columns = {
        'user': {
            'merchant': 'name,description,status,version',
            'merchant_application': 'status,reason,version',
            'user_address': 'recipient,phone,region,address,default_address,deleted,version',
            'user_notification': 'business_key,read_at'
        },
        'product': {'product_image': 'deleted'},
        'order': {'merchant_shipping_rule': 'merchant_id,fee_cents,version', 'trade_group': 'group_no',
                  'order_notification_task': 'source_log_id,lease_owner,claim_version,attempt_count,lease_until,state,last_error,delivered_at,next_retry_at'},
        'aftersale': {'platform_dispute': 'dispute_no,status,decision,decision_reason,decided_by,version,decided_at'},
        'settlement': {
            'settlement_task': 'task_no,lease_owner,claim_version,lease_until,scan_cursor,candidate_cursor,last_error',
            'settlement_account': 'merchant_id,debt_cents',
            'settlement_item': 'refund_cents,fee_cents,status,batch_no'
        }
    }.get(service, {})
    # B5所有参与者都保存本库消息证据，原始载荷和操作审计不授予任意修改权。
    if service in ('order', 'payment', 'aftersale'):
        immutable += ['inbox_message', 'deferred_event', 'message_failure', 'message_operation']
        if service in ('order', 'aftersale'):
            immutable += ['outbox_event']
        if service == 'order':
            immutable += ['event_projection']
    grants = [f"GRANT SELECT,INSERT,UPDATE ON {db}.{table} TO '{app}'@'127.0.0.1';" for table in mutable]
    grants += [f"GRANT SELECT,INSERT ON {db}.{table} TO '{app}'@'127.0.0.1';" for table in immutable]
    grants += [f"GRANT UPDATE({columns}) ON {db}.{table} TO '{app}'@'127.0.0.1';" for table, columns in upgrade_columns.items()]
    if service == 'order':
        receipt_update = mysql(admin, f"SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA='{db}' AND TABLE_NAME='order_return_receipt' AND PRIVILEGE_TYPE='UPDATE' AND GRANTEE=CONCAT(CHAR(39),'{app}',CHAR(39),'@',CHAR(39),'127.0.0.1',CHAR(39));")
        if receipt_update != '0':
            mysql(admin, f"REVOKE UPDATE ON {db}.order_return_receipt FROM '{app}'@'127.0.0.1';")
        grants += [f"GRANT UPDATE(status) ON {db}.order_return_receipt TO '{app}'@'127.0.0.1';"]
    if service == 'aftersale':
        grants += [f"GRANT UPDATE(state) ON {db}.evidence_file TO '{app}'@'127.0.0.1';"]
    if service == 'user':
        grants += [f"GRANT SELECT ON {db}.role TO '{app}'@'127.0.0.1';", f"GRANT DELETE ON {db}.login_rate_limit TO '{app}'@'127.0.0.1';", f"GRANT DELETE ON {db}.login_entry_window TO '{app}'@'127.0.0.1';"]
    if service == 'payment':
        # 升级B4既有账户时收紧原整表UPDATE，避免它覆盖B5的原始消息列保护。
        broad = mysql(admin, f"SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA='{db}' AND TABLE_NAME='outbox_event' AND PRIVILEGE_TYPE='UPDATE' AND GRANTEE=CONCAT(CHAR(39),'{app}',CHAR(39),'@',CHAR(39),'127.0.0.1',CHAR(39));")
        if broad != '0':
            mysql(admin, f"REVOKE UPDATE ON {db}.outbox_event FROM '{app}'@'127.0.0.1';")
    if service in ('order', 'payment', 'aftersale'):
        message_columns = {
            'inbox_message': 'event_id,status,updated_at',
            'deferred_event': 'deferred_id,status,attempt_count,cycle_started_at,next_retry_at,lease_owner,lease_until,claim_version,last_error,updated_at,version',
            'message_failure': 'failure_id,disposition,status,attempt_count,cycle_started_at,next_retry_at,lease_owner,lease_until,claim_version,published_at,last_error,updated_at,version',
            'outbox_event': 'status,attempt_count,next_retry_at,lease_owner,lease_until,claim_version,published_at,last_error,wire_payload,updated_at,version'
        }
        grants += [f"GRANT UPDATE({cols}) ON {db}.{table} TO '{app}'@'127.0.0.1';" for table, cols in message_columns.items()]
    if service == 'aftersale':
        grants += [f"GRANT UPDATE(idempotency_key) ON {db}.aftersale_idempotency TO '{app}'@'127.0.0.1';"]
    if service == 'payment':
        columns = {
            'refund_account': 'reserved_refund_cents,succeeded_refund_cents,version',
            'refund_source': 'source_no,reserved_cents,succeeded_cents,active_refund_no,version',
            'refund_order': 'status,channel_refund_no,channel_version,channel_updated_at,succeeded_at,review_required,last_error,version'
        }
        grants += [f"GRANT UPDATE({cols}) ON {db}.{table} TO '{app}'@'127.0.0.1';" for table, cols in columns.items()]
    if service == 'payment_simulator':
        columns = {
            'channel_transaction': 'payment_no,status,channel_version,updated_at,succeeded_at,reserved_refund_cents,succeeded_refund_cents',
            'fault_scenario': 'payment_no',
            'channel_refund': 'status,channel_version,succeeded_at,updated_at',
            'refund_fault_scenario': 'refund_no',
            'refund_process_task': 'state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error',
            'refund_callback_task': 'state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error',
            'process_task': 'task_no,state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error',
            'callback_task': 'task_no,state,attempt_count,claim_version,lease_owner,lease_until,next_retry_at,deliveries_done,last_error'
        }
        grants += [f"GRANT UPDATE({cols}) ON {db}.{table} TO '{app}'@'127.0.0.1';" for table, cols in columns.items()]
    # B7证据、指纹、请求原因不授予修改权；只开放本库恢复状态和必要首次绑定列。
    if service == 'payment':
        task_columns = 'state,payload,lease_owner,claim_version,lease_until,consecutive_failures,next_at,last_error,updated_at'
        for table, extra in [('reconciliation_run',''), ('reconciliation_issue',',action_no'), ('reconciliation_action',',issue_no,origin_run_no')]:
            grants += [f"GRANT UPDATE({task_columns}{extra}) ON {db}.{table} TO '{app}'@'127.0.0.1';"]
        grants += [f"GRANT UPDATE(result_no) ON {db}.reconciliation_command TO '{app}'@'127.0.0.1';"]
    if service == 'payment_simulator':
        grants += [f"GRANT UPDATE(request_no,manifest_json) ON {db}.bill_snapshot TO '{app}'@'127.0.0.1';",
                   f"GRANT UPDATE(request_no) ON {db}.bill_projection_scenario TO '{app}'@'127.0.0.1';"]
    mysql(admin, '\n'.join(grants))
    return "\n".join(statements).replace("@'127.0.0.1'", "@'%'")
