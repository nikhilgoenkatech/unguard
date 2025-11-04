    [HttpPost("add/{userid}")]
    public async Task<IActionResult> SetMembershipStatus(string userid, [FromForm(Name = "membership")] string membership)
    {
      using (var connection = new MySqlConnection(_connectionString))
      {
        await connection.OpenAsync();

        _logger.LogInformation($"INSERT requested => userid: {userid}, membership: {membership}");

        string query = "INSERT INTO membership (userid, membership) VALUES (@userid, @membership) ON DUPLICATE KEY UPDATE membership = @membership";

        _logger.LogInformation($"Executing query: {query}");

        using (var cmd = new MySqlCommand(query, connection))
        {
          cmd.Parameters.AddWithValue("@userid", userid);
          cmd.Parameters.AddWithValue("@membership", membership);
          try
          {
            int rowsAffected = await cmd.ExecuteNonQueryAsync();
            if (rowsAffected > 0)
            {
              _logger.LogInformation($"Membership status for userid {userid} updated to {membership}");
              return Ok();
            }
            else
            {
              _logger.LogInformation($"Membership status for userid {userid} could not be updated");
              return StatusCode((int)HttpStatusCode.InternalServerError);
            }
          }
          catch (Exception ex)
          {
            return StatusCode((int)HttpStatusCode.InternalServerError, ex.Message);
          }
        }
      }
    }