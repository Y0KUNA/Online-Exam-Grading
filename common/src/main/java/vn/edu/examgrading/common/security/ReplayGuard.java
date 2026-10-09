package vn.edu.examgrading.common.security;

public interface ReplayGuard { boolean firstUse(String requestId); }
