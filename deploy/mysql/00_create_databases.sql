-- TradeFlow 全新环境初始化；必须使用 MySQL 8.0.46；不包含删除或覆盖已有库。
SET NAMES utf8mb4;
SET time_zone = '+00:00';
CREATE DATABASE `trade_user` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE `trade_product` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE `trade_order` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE `trade_payment` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE `trade_aftersale` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE `trade_payment_simulator` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
