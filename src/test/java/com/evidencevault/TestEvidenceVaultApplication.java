package com.evidencevault;

import org.springframework.boot.SpringApplication;

public class TestEvidenceVaultApplication {

	public static void main(String[] args) {
		SpringApplication.from(EvidenceVaultApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
