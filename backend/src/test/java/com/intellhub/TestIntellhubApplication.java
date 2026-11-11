package com.intellhub;

import org.springframework.boot.SpringApplication;

public class TestIntellhubApplication {

	public static void main(String[] args) {
		SpringApplication.from(IntellhubApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
