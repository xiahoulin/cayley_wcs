package com.cayleywcs.application;

public interface ResourceDeletionService {
    boolean deleteApplication(Long id);
    boolean deleteProtocol(Long id);
}
