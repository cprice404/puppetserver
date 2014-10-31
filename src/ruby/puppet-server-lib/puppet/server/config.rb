require 'puppet'
require 'puppet/server'

require 'java'
java_import com.puppetlabs.certificate_authority.CertificateAuthority
java_import java.io.FileReader

class Puppet::Server::Config

  def self.initialize_settings(puppet_server_config)
    @environment_registry = puppet_server_config["environment_registry"]
  end

  def self.ssl_context
    # Initialize an SSLContext for use during HTTPS client requests.
    # Do this lazily due to startup-ordering issues - to give the CA
    # service time to create these files before they are referenced here.
    unless @ssl_context
      @ssl_context = CertificateAuthority.pems_to_ssl_context(
          FileReader.new(Puppet[:hostcert]),
          FileReader.new(Puppet[:hostprivkey]),
          FileReader.new(Puppet[:localcacert]))
    end
    @ssl_context
  end

  def self.environment_registry
    @environment_registry
  end
end
