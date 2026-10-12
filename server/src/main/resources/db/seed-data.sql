-- =============================================================================
-- 种子数据：测试用户、idle_items、help_requests，用于首页展示
-- 可安全重复执行：数据已足够时自动跳过
-- =============================================================================

-- 仅当已分配房间的住户少于 4 人时插入
DO $$
DECLARE
    user_count int;
    r_id UUID;
BEGIN
    SELECT count(*) INTO user_count FROM users WHERE user_type = 'owner' AND room_id IS NOT NULL;
    IF user_count < 4 THEN
        -- 添加已分配房间的用户（取 1栋1单元的前 4 个房间）
        FOR r_id IN SELECT id FROM rooms WHERE unit_id IN (
            SELECT id FROM units WHERE building_id IN (
                SELECT id FROM buildings WHERE name = '1栋' LIMIT 1
            ) AND name = '1单元' LIMIT 1
        ) LIMIT 4
        LOOP
            INSERT INTO users (id, room_id, username, password_hash, user_type, name, phone, auth_status)
            VALUES (gen_random_uuid(), r_id, NULL, NULL, 'owner',
                    CASE (random()*3)::int
                        WHEN 0 THEN '赵邻居'
                        WHEN 1 THEN '孙阿姨'
                        WHEN 2 THEN '周大爷'
                        ELSE '吴小姐'
                    END,
                    '138' || lpad((random()*99999999)::int::text, 8, '0'),
                    'approved')
            ON CONFLICT DO NOTHING;
        END LOOP;
    END IF;
END $$;

-- 仅当闲置物品总数少于 12 时插入
DO $$
DECLARE
    item_count int;
    u1 UUID; u2 UUID; u3 UUID; u4 UUID;
    now_ts timestamp := NOW();
BEGIN
    SELECT count(*) INTO item_count FROM idle_items WHERE status = 'online';
    IF item_count < 12 THEN
        -- 获取测试用户（省略 NULL 检查 — 前面的初始化或上一步已保证其存在）
        SELECT id INTO u1 FROM users WHERE id = '40000000-0000-0000-0000-000000000011';
        SELECT id INTO u2 FROM users WHERE id = '40000000-0000-0000-0000-000000000012';
        SELECT id INTO u3 FROM users WHERE id = '40000000-0000-0000-0000-000000000013';
        -- 再取一位已分配房间的其他住户
        SELECT id INTO u4 FROM users WHERE user_type = 'owner' AND room_id IS NOT NULL
            AND id NOT IN ('40000000-0000-0000-0000-000000000011',
                           '40000000-0000-0000-0000-000000000012',
                           '40000000-0000-0000-0000-000000000013')
            LIMIT 1;

        -- 更多 LEND 物品，覆盖多种分类和价格
        INSERT INTO idle_items (id, user_id, post_type, title, description, category, condition, price, images, max_duration, pickup_method, status, created_at, updated_at)
        VALUES
        (gen_random_uuid(), u1, 'LEND', '戴森吸尘器V8', '只用过2次，很干净，适合深度清洁', '家居', 'like-new', 0, '["/images/demo/vacuum.jpg"]', 3, 'self_pickup', 'online',
         now_ts - INTERVAL '2 hours', now_ts - INTERVAL '2 hours'),
        (gen_random_uuid(), u1, 'LEND', '《三体》全集', '刘慈欣签名版，请爱护书籍', '书籍', 'like-new', 0, '["/images/demo/threebody.jpg"]', 14, 'self_pickup', 'online',
         now_ts - INTERVAL '5 hours', now_ts - INTERVAL '5 hours'),
        (gen_random_uuid(), u2, 'LEND', '折叠梯子2米', '铝合金材质，承重150kg，自取', '工具', 'normal', 0, '["/images/demo/ladder.jpg"]', 7, 'self_pickup', 'online',
         now_ts - INTERVAL '1 day', now_ts - INTERVAL '1 day'),
        (gen_random_uuid(), u2, 'LEND', 'Switch游戏卡带-塞尔达', '旷野之息+王国之泪，一起借', '玩具', 'normal', 20, '["/images/demo/zelda.jpg"]', 14, 'self_pickup', 'online',
         now_ts - INTERVAL '1 day', now_ts - INTERVAL '1 day'),
        (gen_random_uuid(), u2, 'LEND', 'MacBook Pro充电器', '61W USB-C，备用充电器，随时可取', '电子产品', 'normal', 0, '["/images/demo/charger.jpg"]', 30, 'self_pickup', 'online',
         now_ts - INTERVAL '2 days', now_ts - INTERVAL '2 days'),
        (gen_random_uuid(), u3, 'LEND', '露营帐篷4人', '双层防雨，周末露营必备', '运动', 'normal', 10, '["/images/demo/tent.jpg"]', 3, 'self_pickup', 'online',
         now_ts - INTERVAL '3 days', now_ts - INTERVAL '3 days'),
        (gen_random_uuid(), u3, 'LEND', '儿童自行车16寸', '适合3-6岁，带辅助轮', '运动', 'worn', 0, '["/images/demo/bike.jpg"]', 30, 'self_pickup', 'online',
         now_ts - INTERVAL '3 days', now_ts - INTERVAL '3 days'),
        (gen_random_uuid(), u3, 'LEND', '冬季羽绒服男款L码', '只穿过一季，干净整洁', '服饰', 'like-new', 0, '["/images/demo/jacket.jpg"]', 14, 'self_pickup', 'online',
         now_ts - INTERVAL '4 days', now_ts - INTERVAL '4 days'),

        -- 更多 WANTED 物品
        (gen_random_uuid(), u2, 'WANTED', '求借电钻安装窗帘', '需要冲击钻，打几个孔就行，半天归还', '工具', 'worn', 0, NULL, 1, 'self_pickup', 'online',
         now_ts - INTERVAL '6 hours', now_ts - INTERVAL '6 hours'),
        (gen_random_uuid(), u1, 'WANTED', '求借瑜伽垫2天', '家里来客人，临时借用，用完即还', '运动', 'worn', 0, NULL, 2, 'self_pickup', 'online',
         now_ts - INTERVAL '12 hours', now_ts - INTERVAL '12 hours'),
        (gen_random_uuid(), u3, 'WANTED', '求借《活着》余华', '想借来看看，一周内归还', '书籍', 'normal', 0, NULL, 7, 'self_pickup', 'online',
         now_ts - INTERVAL '2 days', now_ts - INTERVAL '2 days'),
        (gen_random_uuid(), u1, 'WANTED', '求借婴儿推车3天', '亲戚带宝宝来访，临时用几天', '其他', 'normal', 0, NULL, 3, 'self_pickup', 'online',
         now_ts - INTERVAL '5 days', now_ts - INTERVAL '5 days'),

        -- 来自额外用户的物品
        (gen_random_uuid(), u4, 'LEND', '烤箱32L家用', '买来没用几次，适合烘焙新手', '家居', 'like-new', 0, '["/images/demo/oven.jpg"]', 7, 'self_pickup', 'online',
         now_ts - INTERVAL '1 day', now_ts - INTERVAL '1 day'),
        (gen_random_uuid(), u4, 'LEND', 'iPad Air 4代', '64G WiFi版，带保护壳', '电子产品', 'normal', 0, '["/images/demo/ipad.jpg"]', 7, 'self_pickup', 'online',
         now_ts - INTERVAL '2 days', now_ts - INTERVAL '2 days')
        ON CONFLICT DO NOTHING;
    END IF;
END $$;

-- 仅当求助信息总数少于 6 时插入
DO $$
DECLARE
    h_count int;
    u1 UUID; u2 UUID; u3 UUID;
    now_ts timestamp := NOW();
BEGIN
    SELECT count(*) INTO h_count FROM help_requests WHERE status = 'online';
    IF h_count < 6 THEN
        SELECT id INTO u1 FROM users WHERE id = '40000000-0000-0000-0000-000000000011';
        SELECT id INTO u2 FROM users WHERE id = '40000000-0000-0000-0000-000000000012';
        SELECT id INTO u3 FROM users WHERE id = '40000000-0000-0000-0000-000000000013';

        INSERT INTO help_requests (id, user_id, title, description, category, is_urgent, time_start, time_end, location, reward_type, images, status, created_at, updated_at)
        VALUES
        (gen_random_uuid(), u1, '网络断了求高手看看', '家里WiFi突然连不上了，路由器灯正常但没网，急求帮忙', '维修', true,
         now_ts + INTERVAL '1 hour', now_ts + INTERVAL '3 hour', '1栋楼下', 'free', NULL, 'online',
         now_ts - INTERVAL '30 minutes', now_ts - INTERVAL '30 minutes'),
        (gen_random_uuid(), u2, '帮忙辅导初二数学', '孩子马上期中考，想找一位数学好的邻居辅导2次', '辅导', false,
         now_ts + INTERVAL '1 day', now_ts + INTERVAL '3 days', '2栋1单元', 'free', NULL, 'online',
         now_ts - INTERVAL '3 hours', now_ts - INTERVAL '3 hours'),
        (gen_random_uuid(), u3, '周末帮忙搬家搬家具', '从5楼搬到3楼，有大衣柜和床需要抬，2个人就够了', '搬运', false,
         now_ts + INTERVAL '2 days', now_ts + INTERVAL '2 days' + INTERVAL '4 hours', '2栋', 'free', NULL, 'online',
         now_ts - INTERVAL '1 day', now_ts - INTERVAL '1 day'),
        (gen_random_uuid(), u1, '求代取快递3个', '最近不在家，有3个快递到了驿站，帮忙拿一下', '代取', false,
         now_ts, now_ts + INTERVAL '2 days', '小区门口菜鸟驿站', 'free', NULL, 'online',
         now_ts - INTERVAL '4 hours', now_ts - INTERVAL '4 hours'),
        (gen_random_uuid(), u2, '找邻居一起晨跑锻炼', '每天早上6:30小区门口出发，5公里左右，想找个伴', '其他', false,
         now_ts, now_ts + INTERVAL '30 days', '小区跑道', 'free', NULL, 'online',
         now_ts - INTERVAL '2 days', now_ts - INTERVAL '2 days'),
        (gen_random_uuid(), u3, '急！求帮做晚饭', '临时加班回不去，需要帮家里老人做一顿晚饭', '烹饪', true,
         now_ts + INTERVAL '2 hours', now_ts + INTERVAL '4 hours', '2栋2单元', 'free', NULL, 'online',
         now_ts - INTERVAL '1 hour', now_ts - INTERVAL '1 hour')
        ON CONFLICT DO NOTHING;
    END IF;
END $$;
