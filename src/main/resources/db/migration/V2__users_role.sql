-- users.role — 운영자 1~2 계정 admin 승격용. 풀 RBAC 은 v2.
-- 기존 row 는 default 'user'. admin 승격은 운영자 SQL 1줄로 수동:
--   UPDATE users SET role='admin' WHERE email='devrel.365@gmail.com';
--
-- CHECK constraint 의 enum 값은 plugins/Auth.kt 의 admin 비교 문자열 ('admin') 과 동기.
ALTER TABLE users
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'user'
    CONSTRAINT users_role_check CHECK (role IN ('user', 'admin'));
