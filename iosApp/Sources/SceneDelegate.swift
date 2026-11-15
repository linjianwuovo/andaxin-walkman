import UIKit
import shared

/*
 * 场景代理：窗口在这儿建。
 *
 * 这一版是壳：一块黑底 + 三行字（标题 / 副标题 / 版本）+ 一段说明。
 * 字全部来自 shared 那个 K/N framework（IosShell.shared.xxx()）——
 * 这样"framework 真的链进去了、真的能被 Swift 调到"这件事开机能看见，
 * 不用再靠 otool 去猜。
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

        let vc = UIViewController()
        vc.view.backgroundColor = .black

        let title = UILabel()
        title.text = IosShell.shared.title()
        title.textColor = .white
        title.font = .systemFont(ofSize: 40, weight: .semibold)

        let subtitle = UILabel()
        subtitle.text = IosShell.shared.subtitle()
        subtitle.textColor = UIColor(white: 1.0, alpha: 0.72)
        subtitle.font = .systemFont(ofSize: 17, weight: .regular)

        let version = UILabel()
        version.text = "版本 " + IosShell.shared.version()
        version.textColor = UIColor(white: 1.0, alpha: 0.45)
        version.font = .systemFont(ofSize: 14, weight: .regular)

        let note = UILabel()
        note.text = IosShell.shared.note()
        note.numberOfLines = 0
        note.textAlignment = .center
        note.textColor = UIColor(white: 1.0, alpha: 0.32)
        note.font = .systemFont(ofSize: 13, weight: .regular)
        note.translatesAutoresizingMaskIntoConstraints = false

        let stack = UIStackView(arrangedSubviews: [title, subtitle, version])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false

        vc.view.addSubview(stack)
        vc.view.addSubview(note)

        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: vc.view.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: vc.view.centerYAnchor, constant: -40),
            note.centerXAnchor.constraint(equalTo: vc.view.centerXAnchor),
            note.leadingAnchor.constraint(greaterThanOrEqualTo: vc.view.leadingAnchor, constant: 32),
            note.trailingAnchor.constraint(lessThanOrEqualTo: vc.view.trailingAnchor, constant: -32),
            note.topAnchor.constraint(equalTo: stack.bottomAnchor, constant: 36),
        ])

        let w = UIWindow(windowScene: windowScene)
        w.rootViewController = vc
        window = w
        w.makeKeyAndVisible()

        NSLog("AndaXin iOS 壳：窗口已建，framework 版本 " + IosShell.shared.version())
    }
}
