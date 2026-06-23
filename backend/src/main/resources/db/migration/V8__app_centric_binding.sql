-- 去除「自绑定」兼容：不再允许应用指挥自己 (upstream_app_id == downstream_app_id)。
-- 绑定语义保持「上位侧 upstream_app_id → 下位侧 downstream_app_id」；但不按应用 direction 锁方向：
-- 任意应用都可出现在上位侧或下位侧（例如「下位→下位」），仅禁止自指。
delete from "wcs_app_binding" where "upstream_app_id" = "downstream_app_id";
