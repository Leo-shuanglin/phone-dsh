// SPDX-License-Identifier: MIT

import XCTest

@MainActor
final class ModelProviderEditorUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(bundleIdentifier: "dev.phone-dsh.probe")
        // Non-secret legacy defaults give the list a deterministic edit target.
        // No credential is entered and no provider request is made by these tests.
        app.launchArguments = [
            "-uiPreview", "settings.model",
            "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
            "-chat.model.baseURL", "https://api.deepseek.com/anthropic",
            "-chat.model.name", "deepseek-flash",
        ]
        app.launch()
        XCTAssertTrue(app.buttons["provider.add"].waitForExistence(timeout: 30))
    }

    override func tearDownWithError() throws {
        app.terminate()
    }

    func testFirstAddOpensANewProviderWithoutLookingUpAnEmptyID() {
        app.buttons["provider.add"].tap()
        assertNewProvider()
    }

    func testEditingThenAddingUsesTheNewMode() {
        let edit = app.buttons["provider.edit-default"]
        XCTAssertTrue(edit.waitForExistence(timeout: 10))
        edit.tap()
        XCTAssertTrue(app.navigationBars["编辑模型服务"].waitForExistence(timeout: 10))
        app.buttons["取消"].tap()
        XCTAssertTrue(app.buttons["provider.add"].waitForExistence(timeout: 10))
        app.buttons["provider.add"].tap()
        assertNewProvider()
    }

    func testCancelDiscardsTheNewProviderDraft() {
        app.buttons["provider.add"].tap()
        assertNewProvider()
        let name = app.textFields["provider.name"]
        let initialName = name.value as? String
        name.tap()
        name.typeText("Unsaved regression draft")
        XCTAssertNotEqual(name.value as? String, initialName)
        app.buttons["取消"].tap()
        XCTAssertTrue(app.buttons["provider.add"].waitForExistence(timeout: 10))
        app.buttons["provider.add"].tap()
        assertNewProvider()
        XCTAssertEqual(app.textFields["provider.name"].value as? String, initialName)
    }

    private func assertNewProvider(file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(app.navigationBars["添加模型服务"].waitForExistence(timeout: 10),
                      file: file, line: line)
        XCTAssertTrue(app.textFields["provider.name"].exists, file: file, line: line)
        let keyLookupError = app.staticTexts.matching(NSPredicate(
            format: "label BEGINSWITH %@", "无法读取此设备的密钥：")).firstMatch
        XCTAssertFalse(keyLookupError.waitForExistence(timeout: 1), file: file, line: line)
    }
}
