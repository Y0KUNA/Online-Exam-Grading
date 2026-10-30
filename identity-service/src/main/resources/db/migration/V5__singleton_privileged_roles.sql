-- These two roles are system-owned singleton accounts; graders are the only self-registering role.
CREATE UNIQUE INDEX uq_users_singleton_privileged_role
    ON users (role)
    WHERE role IN ('EXAM_OFFICE', 'COUNCIL');
