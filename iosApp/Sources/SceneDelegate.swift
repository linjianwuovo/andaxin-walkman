import UIKit
import shared

/*
 * 场景代理：窗口在这儿建。
 *
 * 界面本体已经不是 Swift 了 —— 底栏那五格、首页、课表、消息、公告、我的，
 * 全部在 shared 的 commonMain 里用 Compose Multiplatform 画（MainViewController.kt
 * 里的 MainViewController()），这里只把它塞进 window。
 *
 * 这样做的好处是 Android 与 iOS 共用同一套 Kotlin 界面代码；代价是 iOS 侧的
 * 玻璃底（glasmio）这一版还没跟过来，先用纯色板顶上。
 */
@objc(SceneDelegate)
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let root = MainViewControllerKt.MainViewController()
        root.view.backgroundColor = .black

        let w = UIWindow(windowScene: windowScene)
        w.rootViewController = root
        window = w
        w.makeKeyAndVisible()

        NSLog("AndaXin iOS " + IosShell.shared.version() + "：Compose 界面已挂上")
    }
}
