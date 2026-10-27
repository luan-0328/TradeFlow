-- B5 支付库新迁移模板；仅追加冻结正文，绝不修改已执行的 V1/V2。
ALTER TABLE outbox_event
  ADD COLUMN wire_payload LONGTEXT NULL COMMENT '首次领取冻结的完整事件信封UTF8正文，后续发布始终保持相同字节' AFTER payload;
