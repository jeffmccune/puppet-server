step "Configure the SUT to work with new AIO Layout specified at: https://github.com/puppetlabs/puppet-specifications/blob/af825090dba94c56d332b0e61d73395eb6eb0535/file_paths.md#puppet-server"
# These hacks need to be incorporated into beaker itself.

# See: https://github.com/puppetlabs/puppet-specifications/blob/af825090dba94c56d332b0e61d73395eb6eb0535/file_paths.md#puppet-server
# End users are expected to execute puppetserver by having /opt/puppetlabs/bin in their PATH
step "AIO: Add /opt/puppetlabs/bin to PATH (REMOVE ONCE https://tickets.puppetlabs.com/browse/QENG-1891 is resolved and employed)" do
  hosts.each do |host|
    # /opt/puppetlabs/bin is present to get puppetserver
    # /opt/puppetlabs/puppet/bin is present to get facter
    on host, 'sed -i s,PATH=,PATH=/opt/puppetlabs/bin:/opt/puppetlabs/puppet/bin:, ~/.ssh/environment'
  end
end

test_name 'AIO: (PUP-3997) Remove this work around once PUP-3997 is resolved' do
  agents.each do |agent|
    if agent == master
      step "Skipping creating puppet user and group on #{agent}"
    else
      step "Ensure puppet user and group added to #{agent}" do
        on agent, puppet("resource user puppet ensure=present")
        on agent, puppet("resource group puppet ensure=present")
      end
    end
  end
end
