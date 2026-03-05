package com.genai.bi;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BiApplication {

	public static void main(String[] args) {

		Dotenv dotenv = Dotenv.load();

		System.setProperty("OPENROUTER_API_KEY", dotenv.get("OPENROUTER_API_KEY"));
		System.setProperty("OPENROUTER_EMBEDDING_MODEL", dotenv.get("OPENROUTER_EMBEDDING_MODEL"));
		System.setProperty("OPENROUTER_EMBEDDING_URL", dotenv.get("OPENROUTER_EMBEDDING_URL"));

		System.setProperty("GROQ_API_KEY", dotenv.get("GROQ_API_KEY"));
		System.setProperty("GROQ_CHAT_MODEL", dotenv.get("GROQ_CHAT_MODEL"));
		System.setProperty("GROQ_CHAT_URL", dotenv.get("GROQ_CHAT_URL"));

		SpringApplication.run(BiApplication.class, args);
	}
}