-- user_type CHECK 约束统一为英文值域（owner/tenant/admin/senior_admin/super_admin）
-- 顺序固定：DROP 约束 → 迁移存量中文值 → 归一化默认值 → 重建约束。
-- 幂等：可重复执行；已是英文值时 UPDATE 不命中任何行。
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_user_type_check;
UPDATE users SET user_type = 'owner'  WHERE user_type = '业主';
UPDATE users SET user_type = 'tenant' WHERE user_type = '租客';
ALTER TABLE users ALTER COLUMN user_type SET DEFAULT 'owner';
ALTER TABLE users ADD CONSTRAINT users_user_type_check
    CHECK (user_type IN ('owner', 'tenant', 'admin', 'senior_admin', 'super_admin'));
