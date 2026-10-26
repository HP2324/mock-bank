-- Demo data the automation artifacts depend on (see README "Mock app states").
INSERT INTO members (member_id, name, status, can_open_sub_account) VALUES
    ('12345', 'Jordan Lee',   'Active',     TRUE),
    ('24680', 'Maya Chen',    'Active',     TRUE),
    ('77777', 'Riley Morgan', 'Restricted', FALSE);

INSERT INTO accounts (member_id, account_number, account_type, balance, opened_at) VALUES
    ('12345', 'SAV-1001', 'Primary Savings',   1842.77,  TIMESTAMPTZ '2021-03-14 10:00:00+00'),
    ('12345', 'CHK-2042', 'Everyday Checking',  620.14,  TIMESTAMPTZ '2021-03-14 10:05:00+00'),
    ('24680', 'SAV-9820', 'Primary Savings',   9320.01,  TIMESTAMPTZ '2019-07-02 15:30:00+00'),
    ('24680', 'MM-1180',  'Money Market',     15100.00,  TIMESTAMPTZ '2020-01-20 09:15:00+00'),
    ('77777', 'CHK-7770', 'Everyday Checking',   88.45,  TIMESTAMPTZ '2022-11-08 13:45:00+00');
