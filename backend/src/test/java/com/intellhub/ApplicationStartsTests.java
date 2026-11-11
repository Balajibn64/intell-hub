package com.intellhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.datasource.password=unused-testcontainers-overrides")
@AutoConfigureMockMvc
class ApplicationStartsTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void flywayAppliesBaselineMigration() {
		Integer applied = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
		assertThat(applied).isEqualTo(1);
	}

	@Test
	void healthEndpointIsUpAndPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void everythingElseIsDenied() throws Exception {
		mockMvc.perform(get("/api/v1/anything")).andExpect(status().isForbidden());
	}

}
