#!/usr/bin/env ruby
# frozen_string_literal: true
#
# Keeps LabImporter.xcodeproj in sync with the folders on disk, so the
# project file never needs hand edits:
#
#   LabImporter/         the app's Swift sources (groups mirror folders)
#   LabImporterTests/    unit tests (Swift Testing), hosted in the app
#   LabImporterUITests/  UI tests (XCTest/XCUITest)
#   LabImporterShare/    the share extension (text → the app via an App Group),
#                        embedded in the app; also compiles LabImporter/Services/ShareInbox.swift
#
# Idempotent: creates missing targets/groups, adds new *.swift files to their
# target, removes references to deleted files, and makes sure the shared
# LabImporter scheme tests both bundles.
#
# Usage: GEM_HOME=~/.gem/ruby/2.6.0 ruby tools/sync_xcode_project.rb
#        (needs the `xcodeproj` gem: gem install xcodeproj --user-install)

require 'xcodeproj'

ROOT = File.expand_path('..', __dir__)
PROJECT_PATH = File.join(ROOT, 'LabImporter.xcodeproj')
APP_TARGET = 'LabImporter'
DEPLOYMENT = '26.0'

project = Xcodeproj::Project.open(PROJECT_PATH)
app = project.targets.find { |t| t.name == APP_TARGET } or abort "no #{APP_TARGET} target"
config_ref = project.files.find { |f| f.path == 'Config.xcconfig' } or abort 'no Config.xcconfig reference'

def common_settings(settings)
  settings['IPHONEOS_DEPLOYMENT_TARGET'] = DEPLOYMENT
  settings['SWIFT_VERSION'] = '6.0'
  settings['TARGETED_DEVICE_FAMILY'] = '1,2'
  settings['GENERATE_INFOPLIST_FILE'] = 'YES'
  settings['CODE_SIGN_STYLE'] = 'Automatic'
  settings['SDKROOT'] = 'iphoneos'
  settings['SWIFT_EMIT_LOC_STRINGS'] = 'NO'
  settings['PRODUCT_NAME'] = '$(TARGET_NAME)'
end

def ensure_target(project, app, config_ref, name, type, folder)
  target = project.targets.find { |t| t.name == name }
  unless target
    target = project.new_target(type, name, :ios, DEPLOYMENT, nil, :swift)
    target.add_dependency(app)
    puts "created target #{name}"
  end
  target.build_configurations.each do |config|
    config.base_configuration_reference = config_ref
    settings = config.build_settings
    common_settings(settings)
    yield settings
  end
  group = project.main_group.children.find { |g| g.display_name == folder } ||
          project.main_group.new_group(folder, folder)
  sync_sources(target, group, File.join(ROOT, folder))
  target
end

def sync_sources(target, group, dir)
  on_disk = Dir.glob(File.join(dir, '**', '*.swift')).sort
  existing = group.recursive_children.select { |c| c.is_a?(Xcodeproj::Project::Object::PBXFileReference) }
  # Drop references to files that no longer exist.
  existing.each do |ref|
    next if File.exist?(ref.real_path)
    target.source_build_phase.remove_file_reference(ref)
    ref.remove_from_project
    puts "removed #{ref.path}"
  end
  on_disk.each do |path|
    rel = path.sub("#{dir}/", '')
    ref = group.recursive_children.find { |c| c.respond_to?(:real_path) && c.real_path.to_s == path }
    unless ref
      ref = group.new_reference(rel)
      puts "added #{rel}"
    end
    target.source_build_phase.add_file_reference(ref, true)
  end
end

# App sources: every *.swift under LabImporter/ belongs to the app target.
def group_for(root_group, parts)
  parts.reduce(root_group) do |group, part|
    group.children.find { |c| c.is_a?(Xcodeproj::Project::Object::PBXGroup) && (c.path == part || c.display_name == part) } ||
      group.new_group(part, part)
  end
end

app_group = project.main_group.children.find { |g| g.display_name == 'LabImporter' } or abort 'no LabImporter group'
app_dir = File.join(ROOT, 'LabImporter')
app_refs = app.source_build_phase.files_references.compact
app_refs.each do |ref|
  next unless ref.path.to_s.end_with?('.swift')
  next if File.exist?(ref.real_path)
  app.source_build_phase.remove_file_reference(ref)
  ref.remove_from_project
  puts "app: removed #{ref.path}"
end
Dir.glob(File.join(app_dir, '**', '*.swift')).sort.each do |path|
  next if app.source_build_phase.files_references.compact.any? { |r| r.real_path.to_s == path }
  rel_dir = File.dirname(path.sub("#{app_dir}/", ''))
  group = rel_dir == '.' ? app_group : group_for(app_group, rel_dir.split('/'))
  ref = group.files.find { |f| f.real_path.to_s == path } || group.new_reference(File.basename(path))
  app.source_build_phase.add_file_reference(ref, true)
  puts "app: added #{path.sub("#{ROOT}/", '')}"
end

unit = ensure_target(project, app, config_ref, 'LabImporterTests', :unit_test_bundle, 'LabImporterTests') do |s|
  s['PRODUCT_BUNDLE_IDENTIFIER'] = '$(BUNDLE_IDENTIFIER).tests'
  s['TEST_HOST'] = '$(BUILT_PRODUCTS_DIR)/LabImporter.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/LabImporter'
  s['BUNDLE_LOADER'] = '$(TEST_HOST)'
end

# Golden fixtures shared with the Kotlin tests ship inside the unit-test bundle.
golden_group = project.main_group.children.find { |g| g.display_name == 'Golden' } ||
               project.main_group.new_group('Golden', 'shared/src/jvmTest/golden')
golden_dir = File.join(ROOT, 'shared/src/jvmTest/golden')
golden_group.files.each do |ref|
  next if File.exist?(ref.real_path)
  unit.resources_build_phase.remove_file_reference(ref)
  ref.remove_from_project
end
Dir.glob(File.join(golden_dir, '*')).sort.each do |path|
  name = File.basename(path)
  ref = golden_group.files.find { |f| f.path == name } || golden_group.new_reference(name)
  unit.resources_build_phase.add_file_reference(ref, true)
end

ui = ensure_target(project, app, config_ref, 'LabImporterUITests', :ui_test_bundle, 'LabImporterUITests') do |s|
  s['PRODUCT_BUNDLE_IDENTIFIER'] = '$(BUNDLE_IDENTIFIER).uitests'
  s['TEST_TARGET_NAME'] = APP_TARGET
end

# The version is set once, in Config.xcconfig (shared with Android), which
# every target uses — so it never belongs in the project file.
(project.build_configurations + app.build_configurations).each { |c| c.build_settings.delete('MARKETING_VERSION') }

# The build number lives at the project level so the app and its extension
# always match (App Store requirement); agvtool/fastlane bump it there.
value = app.build_configurations.map { |c| c.build_settings['CURRENT_PROJECT_VERSION'] }.compact.first
if value
  project.build_configurations.each { |c| c.build_settings['CURRENT_PROJECT_VERSION'] = value }
  app.build_configurations.each { |c| c.build_settings.delete('CURRENT_PROJECT_VERSION') }
end

# Share extension: receives text and hands it to the app (see ShareInbox).
SHARE_TARGET = 'LabImporterShare'
share = project.targets.find { |t| t.name == SHARE_TARGET }
unless share
  share = project.new_target(:app_extension, SHARE_TARGET, :ios, DEPLOYMENT, nil, :swift)
  puts "created target #{SHARE_TARGET}"
end
share.build_configurations.each do |config|
  config.base_configuration_reference = config_ref
  s = config.build_settings
  common_settings(s)
  s['GENERATE_INFOPLIST_FILE'] = 'NO'
  s['INFOPLIST_FILE'] = "#{SHARE_TARGET}/Info.plist"
  s['CODE_SIGN_ENTITLEMENTS'] = "#{SHARE_TARGET}/#{SHARE_TARGET}.entitlements"
  s['PRODUCT_BUNDLE_IDENTIFIER'] = '$(BUNDLE_IDENTIFIER).share'
  s['SKIP_INSTALL'] = 'YES'
  s['APPLICATION_EXTENSION_API_ONLY'] = 'YES'
  s['SWIFT_EMIT_LOC_STRINGS'] = 'YES'
  s['LD_RUNPATH_SEARCH_PATHS'] = ['$(inherited)', '@executable_path/Frameworks', '@executable_path/../../Frameworks']
end
share_group = project.main_group.children.find { |g| g.display_name == SHARE_TARGET } ||
              project.main_group.new_group(SHARE_TARGET, SHARE_TARGET)
sync_sources(share, share_group, File.join(ROOT, SHARE_TARGET))
%w[Info.plist LabImporterShare.entitlements].each do |name|
  share_group.files.find { |f| f.path == name } || share_group.new_reference(name)
end
# Shared with the app: the inbox and the string catalog.
inbox_ref = app.source_build_phase.files_references.compact.find { |r| r.path == 'ShareInbox.swift' } or abort 'no ShareInbox.swift in the app'
share.source_build_phase.add_file_reference(inbox_ref, true)
catalog_ref = app.resources_build_phase.files_references.compact.find { |r| r.path.to_s.end_with?('Localizable.xcstrings') } or abort 'no string catalog'
share.resources_build_phase.add_file_reference(catalog_ref, true)
# The app embeds (and therefore builds) the extension.
app.add_dependency(share) unless app.dependencies.any? { |d| d.target == share }
embed = app.copy_files_build_phases.find { |p| p.name == 'Embed Foundation Extensions' } ||
        app.new_copy_files_build_phase('Embed Foundation Extensions')
embed.symbol_dst_subfolder_spec = :plug_ins
unless embed.files_references.include?(share.product_reference)
  embed.add_file_reference(share.product_reference, true).settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }
  puts "app: embeds #{SHARE_TARGET}"
end

project.save

# Shared scheme: run both bundles in the Test action.
scheme_path = File.join(PROJECT_PATH, 'xcshareddata', 'xcschemes', "#{APP_TARGET}.xcscheme")
scheme = Xcodeproj::XCScheme.new(scheme_path)
[unit, ui].each do |target|
  next if scheme.test_action.testables.any? { |t| t.buildable_references.any? { |b| b.target_name == target.name } }
  scheme.test_action.add_testable(Xcodeproj::XCScheme::TestAction::TestableReference.new(target))
  puts "scheme: testing #{target.name}"
end
scheme.save!
puts 'project in sync'
